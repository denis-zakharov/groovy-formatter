# AGENTS.md

Notes for whoever (human or agent) works on this codebase next. This is not a tutorial on the
code — it's the reasoning behind decisions that aren't obvious from reading the code alone, and
the mistakes already made once so they don't get made again.

## What this is

A Groovy source-code formatter, built from scratch, that understands the real Groovy grammar
instead of doing line/regex-based reformatting. Public API: `dev.groovyfmt.print.GroovyFormatter.format(String)`.

The single biggest design decision: **we reuse Groovy's own parser** (the "Parrot" ANTLR4 grammar
shipped inside `org.apache.groovy:groovy`) instead of writing a Groovy grammar from scratch.
Reimplementing Groovy's grammar (GStrings, command-chain calls, optional parens/semicolons,
closures, multiple assignment...) is a multi-month effort on its own; the compiler's own parser
already solves it correctly. Everything else in the architecture follows from that choice.

## Module map

```
formatter-doc        Wadler/Prettier-style pretty-printing IR (Doc, DocRenderer). Zero Groovy
                      knowledge. Pure, fully unit-testable in isolation.
formatter-parser      Wraps GroovyLangLexer/GroovyLangParser construction (GroovyCstParser).
                      The ONLY module that imports groovyjarjarantlr4.v4.runtime.* — the raw
                      shaded ANTLR4 runtime (Token, CommonTokenStream, ANTLRErrorListener, ...).
formatter-comments    Comment extraction + attachment (Comment, TokenStreamComments,
                      CommentAttacher). Pure data — never builds a Doc, never calls the printer.
formatter-print       DocPrintingVisitor: walks the CST, emits Doc. GroovyFormatter is the
                      public entry point.
formatter-cli         picocli-based `groovy-format` CLI (GroovyFormatCommand + GroovyFileFinder):
                      formats one or more files/directories with --in-place, --check,
                      --recursive, --help, --version.
formatter-testkit     Scaffolded, currently empty. Intended home for a vendored real-world
                      corpus + idempotency/semantic-equivalence test harness (see "Testing"
                      below) — this never got built out; see "What's missing" at the bottom.
```

Dependency direction: `formatter-parser` → `formatter-comments`, `formatter-print` →
`formatter-comments` + `formatter-parser` + `formatter-doc` → `formatter-cli`.

### Module boundary: why `formatter-parser` uses `api`, not `implementation`

The original plan said "formatter-parser is the only module that touches Groovy's ANTLR4 types."
That's true for the **shaded runtime package** (`groovyjarjarantlr4.v4.runtime.*` — the low-level
plumbing: `Token`, `CommonTokenStream`, `ANTLRErrorListener`) but turned out to be unworkable for
the **CST node types** (`org.apache.groovy.parser.antlr4.GroovyParser$*Context` — there are ~200
of them). `formatter-print` and `formatter-comments` inherently have to walk those directly; they
*are* the parse tree. Wrapping all 200 in adapter types would just reimplement the CST as a
parallel hierarchy for no benefit. So `formatter-parser`'s Gradle dependency on `org.apache.groovy:groovy`
is declared `api` (not `implementation`), deliberately exposing the whole jar — including the CST
types — to every downstream module. The boundary that's actually enforced is narrower: only
`GroovyCstParser` constructs a `GroovyLangLexer`/`GroovyLangParser` or touches
`groovyjarjarantlr4.v4.runtime.*` directly.

## Load-bearing facts about the Groovy jar (verified against `4.0.29`, not assumed)

These shape multiple parts of the design and will need re-verifying if the pinned Groovy version
ever changes:

- **No real `org.antlr` package exists in the jar.** Groovy relocates its own ANTLR4 runtime
  under `groovyjarjarantlr4`. Never add a real `org.antlr:antlr4-runtime` dependency anywhere in
  this project — a second, incompatible ANTLR type hierarchy would result.
- **Comments are not a distinct token type.** Both `//` and `/* */` are lexed with their token
  *type* rewritten to `NL` (`GroovyLexer.g4`: `-> type(NL)`). Comments must be found by
  inspecting `token.getText()` for a `//`/`/*` prefix — **never** by token type or channel.
  Channel placement (default vs. hidden) depends on paren-nesting (`ignoreTokenInsideParens()`),
  so the *same* comment text can land on either channel depending on where it sits — the
  classifier in `TokenStreamComments` must stay text-only.
- **`WS` (whitespace) is lexer-`skip`ped — it never becomes a token at all**, not even on a
  hidden channel. This means the exact spacing between tokens is unrecoverable from the parse
  tree by any means. This is *why* GStrings are printed 100% verbatim by slicing the raw source
  string via character offsets (`ctx.getStart().getStartIndex()` / `getStop().getStopIndex()`)
  rather than reconstructed from visited child nodes — reconstruction would silently normalize
  away the user's original interpolation-expression spacing.
- **A shebang line (`#!/usr/bin/env groovy`) is also lexer-`skip`ped** — it never appears in the
  CST at all. `GroovyFormatter.format()` peels it off the raw source text before/around parsing
  and reattaches it verbatim to the output; the parser itself handles a shebang-prefixed file
  fine without any stripping (confirmed empirically, not assumed).
- **`<<` / `>>` / `>>>` are lexed as 2–3 adjacent `<`/`>` tokens**, not single shift tokens (to
  avoid ambiguity with nested generics like `Map<List<String>>`). The operator text is
  reconstructed by counting `ctx.LT()`/`ctx.GT()` list sizes in `visitShiftExprAlt`, not by
  trusting the `dlOp`/`tgOp`/`dgOp` semantic-action fields (their exact behavior isn't documented
  anywhere reachable from outside the grammar source).
- **ANTLR's `DefaultErrorStrategy` silently recovers from a syntax error and keeps parsing**,
  producing a best-effort (partially garbage) tree, rather than throwing. `GroovyCstParser`
  installs a `ThrowingErrorListener` on both the lexer and parser that converts any recorded
  error into `GroovyParseException` once parsing finishes — found necessary by a real-world
  corpus survey where malformed/unsupported-syntax input was silently producing corrupted output
  instead of a clear failure.

## The core discipline: never silently drop content

This is the single most important invariant in the codebase, and it has been violated twice by
accident — both times found only by actually running the formatter on real code, never by
reasoning about the code in the abstract.

**The rule**: every construct outside the currently-supported subset must `throw
unsupported(ctx, "description")` (see `DocPrintingVisitor.unsupported()`) — never fall through to
ANTLR's default `visitChildren` behavior. The default visitor returns whatever the *last*
non-null child visit produced, or `null`. A `null` `Doc` inside `Docs.concat(...)` throws a bare
`NullPointerException` deep in `DocRenderer` (unhelpful, but at least loud) — but the more
dangerous failure mode is when the default aggregation happens to return *some* non-null `Doc`
from a child, silently discarding everything else in the node (operands, operators, whole
subtrees). That's not a crash — it's confidently-wrong output.

**What actually happened**: `visitAndExprAlt`/`visitExclusiveOrExprAlt`/`visitInclusiveOrExprAlt`
(`&`, `^`, `|`) had no visitor override at all. `a | b` silently formatted as `b` — the left
operand and the operator vanished, no exception, no warning. It was never caught by any
hand-written unit test because nobody thought to write one exercising bitwise operators. It *was*
caught, eventually, by a corpus survey against real code, via the one file where it made output
non-idempotent.

**The fix, and the standing discipline**: every ANTLR "labeled alternative" class (the ones ANTLR
generates for a rule with `# SomeLabel` alternatives — `ExpressionContext`, `StatementContext`,
`PrimaryContext`, `LiteralContext`, `LoopStatementContext`, and `StatementExpressionContext` all
have families of these) is a `...AltContext` class. **Before considering a grammar area "done,"
audit that every such class has an explicit `visit<Name>` override** — implementing it, or
explicitly throwing `unsupported(...)`. To re-run this audit:

```bash
# List every GroovyParser$*AltContext class from the pinned jar:
JAR=$(find ~/.gradle/caches -name "groovy-4.0.29.jar" | head -1)
unzip -l "$JAR" | grep 'antlr4/GroovyParser\$' | awk '{print $4}' \
  | sed -E 's#.*GroovyParser\$([A-Za-z0-9_]+)(\$[0-9]+)?\.class#\1#' | sort -u \
  | grep 'AltContext$' | sed 's/Context$//' > /tmp/alt_classes.txt

# Cross-reference against the visitor:
VISITOR=formatter-print/src/main/java/dev/groovyfmt/print/DocPrintingVisitor.java
while read -r name; do
  grep -q "visit${name}\b" "$VISITOR" || echo "MISSING: $name"
done < /tmp/alt_classes.txt
```

As of this writing, all ~55 `*AltContext` classes have an explicit override; this should read
zero lines when re-run. Run it again after any Groovy version bump (new grammar alternatives may
have been added) and whenever adding support for a new syntax area.

## Investigation methodology (how every phase of this project was actually built)

**Never guess grammar shapes from memory of Java/Groovy syntax.** The accessor names, field
types, and tree structure of Groovy's ANTLR4 grammar are frequently surprising (see "load-bearing
facts" above) and change between grammar rules in ways that aren't predictable. Two tools were
used throughout, and should be used again for any new grammar area:

1. **`javap` the relevant `GroovyParser$XxxContext` class** directly from the pinned jar:
   ```bash
   JAR=$(find ~/.gradle/caches -name "groovy-4.0.29.jar" | head -1)
   javap -p -classpath "$JAR" "org.apache.groovy.parser.antlr4.GroovyParser\$SomeContext"
   ```
   This gives the exact accessor methods/fields available — including labeled fields (e.g.
   `ctx.left`/`ctx.op`/`ctx.right` on binary-expression alt classes), which are not discoverable
   any other way.

2. **Dump the real parse tree** for representative snippets using a tiny throwaway Java program
   (`GroovyLangLexer`/`GroovyLangParser` + a recursive `ParseTree` printer that prints each
   node's class simple name and, for terminals, the token text). This reveals the *actual* tree
   shape — which layers wrap which, which nodes are absent when a construct is empty
   (`ClassOrInterfaceModifiersOptContext` prints as `""` when empty but is still present; some
   other `Opt`-suffixed contexts are entirely absent instead), and non-obvious sharing (e.g. a
   trailing `{ it * 2 }` closure argument has a *different* CST shape — no `arguments()` at all —
   than the same call written with parens).

3. **After implementing, verify against real code via the CLI, not just hand-written unit
   tests.** Every non-trivial bug found this project (the annotation-inline-instead-of-own-line
   bug, the spurious `def` on untyped closure params, the range-operator spacing bug, the
   `assert` colon-vs-comma bug, the trailing-comma-in-formal-parameters bug, all four bitwise/
   power/regex/cast omissions) was found by running the formatter on a realistic multi-construct
   sample and reading the output, not by reasoning about the printer code in the abstract.
   **Idempotency** (`format(format(x)) == format(x)`) is the cheapest, highest-signal check —
   it's what caught the trailing-comma bug specifically, since the first-pass output looked
   correct and only failed to *re-parse*.

## Style decisions (opinionated, not grammar-mandated — document if changed)

- 4-space indent, 100-column width by default — configurable via `RenderOptions` (`maxWidth`,
  `indentWidth`, `indentStyle`: `SPACES`/`TABS`), and via the CLI's `--line-length`/`-w`,
  `--indent-size`/`-x`, `--use-tabs` flags. `GroovyFormatter.format(String)` still exists using
  `RenderOptions.defaults()`; pass a second `RenderOptions` argument to override. Note: for
  `TABS`, `indentWidth` is used only to compute line-fitting width (one tab ≈ `indentWidth`
  columns, since actual tab-stop width is a viewer setting) — the emitted indentation is one tab
  character per level, not `indentWidth` tabs.
- Triple-quoted strings/GStrings (`'''...'''`, `"""..."""`) that span multiple source lines are
  printed verbatim (see below) except for their own indentation: continuation lines are reindented
  by whatever delta the statement/expression containing them moved by, so relative indentation
  survives the string being reformatted into a different nesting depth. Implemented as
  `Doc.IndentedVerbatim(raw, baseIndent)` in `formatter-doc`, rendered by `DocRenderer`:
  `baseIndent` is the leading-whitespace run of the source line the literal started on; each
  continuation line has that prefix stripped (only as much of it as is actually present — no
  assumption every line is indented at least that far) and the literal's *new* render-time indent
  level prepended. `DocPrintingVisitor` opts a verbatim span into this only when it starts with
  `'''`/`"""` and contains a real `\n` — a same-line (non-multiline) string/GString is unaffected.
  This only shifts indentation; the string's actual content (SQL, a config template, whatever) is
  never parsed or reformatted, consistent with "GString contents are never reformatted" below —
  **except** for the one case below.
- **The sole triple-quoted-string argument of a bare `sh(...)`/`sh '''...'''` call (the Jenkins
  pipeline shell step) actually gets its body run through `formatter-shell`'s `ShellFormatter`**,
  not just reindented — this is the one place a string's *content* is reformatted, not merely
  repositioned. Detected structurally in `DocPrintingVisitor` (`isShellStepCalleeText`, checked at
  both the paren-less command-expression call site and the parenthesized path-expression call
  site — a call like `foo.sh(...)` on some other receiver does NOT match, only a bare `sh`
  identifier callee with exactly one argument) via a scoped `argumentIsShellScript` boolean field
  set around visiting that single argument (see `printExpressionListElement`'s two-arg overload).
  `printVerbatimSourceSpan`/`tryPrintAsShellScript` then: strips the outer quotes, runs the body
  through `ShellFormatter.format(body, renderOptions)` (same `RenderOptions` — indent size/style,
  max width — as the outer Groovy format, so nested shell follows the same conventions), and
  rebuilds an `IndentedVerbatim` from the *formatted* shell text (one indent level deeper than the
  statement, closing quote back at the statement's own level) so it still tracks reindentation the
  normal way. **Falls back to the plain verbatim reindent** (returns `null`, caught by the caller)
  whenever the body doesn't start with its own newline, or `ShellFormatter.format` throws — a
  `RuntimeException` from unsupported syntax (e.g. `arr=(a b c)`, see below) or genuinely invalid
  shell (including a body that isn't shell at all, or Groovy interpolation the shell lexer can't
  make sense of) — so a `sh` step containing something the shell formatter can't yet handle never
  fails the whole Jenkinsfile format, it just keeps its previous (reindent-only) behavior for that
  one block. GString interpolation (`${name}`) inside the body round-trips unchanged because the
  shell lexer already treats `${...}` as an opaque, verbatim-printed parameter-expansion token
  (`scanBalancedBrace` in `formatter-shell`'s `Lexer` — see its own section below) regardless of
  what's actually inside the braces.
- A single-statement closure body or `case`/`default` body stays on one line when it fits
  (`list.each { it * 2 }`, `case 1 -> "one"`); two or more statements always break. Groovy has no
  implicit multi-statement-per-line separator, so this isn't optional once there's more than one
  statement.
- Blank lines between statements/members are **preserved exactly as in source, capped at one,
  and never invented** — including between package/imports/class, which earlier phases forced a
  blank line for and later corrected to pure preservation. This applies uniformly; there's no
  special-casing for "looks like a Gradle DSL block" even though Gradle's own convention would
  prefer `plugins {}`/`repositories {}` to always break — see the Phase 6 CLI output on a real
  `build.gradle`, where `plugins { id 'java' }` legitimately collapses to one line under this
  rule. This is a known, deliberate tradeoff, not a bug.
- Declaration-level annotations (`@Override`, `@Deprecated` on a class/method/field) always go on
  their own line. Parameter-level annotations (`void f(@NotNull String x)`) stay inline. These
  are genuinely different, established conventions — don't unify them.
- Trailing commas: **yes** for argument lists, list literals, and map literals when they wrap.
  **Never** for formal parameter lists — Groovy's grammar doesn't accept one there, and adding
  one produces output that fails to re-parse (a real bug, fixed; the idempotency test is what
  would catch a regression here since the first pass looks fine).
- The paren-less command-expression call form (`foo bar: 1, baz: 2`) is preserved without adding
  parentheses — this is usually a deliberate DSL-style choice (Gradle, Spock) and dropping/adding
  parens would be an uninvited rewrite, not a reformat.
- GString (interpolated string) contents are never reformatted, full stop — printed verbatim via
  raw-source character-offset slicing (see "load-bearing facts" above for why).

## What's out of scope (throws a clear `UnsupportedOperationException`, verified not to crash)

Multi-word command chains (`foo bar baz`, no comma/colon), try-with-resources, anonymous inner
class bodies, array creation expressions (`new int[5]`), `sealed`/`permits`, record compact
constructors, parameter default values, comments nested inside a single expression/statement
(only comments *between* statements/members are attached — see `CommentAttacher`), explicit
generic type witnesses on calls (`Collections.<String>emptyList()`), multiple-assignment/tuple
destructuring (`def (a, b) = [...]` and the def-less form), annotation type declarations
(`@interface Foo {}`).

## Testing

- `formatter-doc` and `formatter-comments` are unit-tested in complete isolation from the rest of
  the pipeline (the whole point of splitting them out).
- `formatter-print` has per-phase test files (`GroovyFormatterTest`, `GroovyFormatterPhase4Test`
  through `Phase7Test`, `GroovyFormatterCorpusFixesTest`) — mostly exact-match `assertEquals` on
  known-good output plus a shared `formattingIsIdempotent` parameterized test per file.
- **What's missing, deliberately deferred**: the original plan called for `formatter-testkit` to
  hold a vendored, curated snapshot of real-world `.groovy` files (from apache/groovy's own
  compiler test suite, spockframework/spock, real Gradle build scripts) as a standing regression
  corpus, run via idempotency + semantic-equivalence (AST diff, position-independent) checks in
  CI. This never got built — the one corpus survey that was run was a one-off exploratory fork
  (clone repos to scratch space, batch-run, report, clean up) rather than a committed fixture set.
  If revisiting this, the survey fork's methodology (see git history / conversation log around
  the "broaden the test corpus" request) is a reasonable starting point: it found 4 real bugs
  (3 silently-dropped binary operators plus a parser-breaking trailing comma) that no
  hand-written test had caught.

## Build / run

```bash
./gradlew build                                    # full build + all tests
./gradlew test                                      # all tests only
./gradlew :formatter-cli:run --args="path/to/File.groovy"   # format one file, prints to stdout
./gradlew :formatter-cli:run --args="-i path/to/File.groovy"   # format in place
./gradlew :formatter-cli:run --args="-r --check src"            # CI-style check over a directory
```

Java 17 toolchain (set per-module via the root `build.gradle.kts` `subprojects {}` block). Pinned
Groovy version: `4.0.29` (see `formatter-parser/build.gradle.kts`) — bump deliberately, and
re-verify the "load-bearing facts" above (especially the shading assumption and the `*AltContext`
audit) against the new jar before trusting it.

## Native image (`formatter-cli/build.gradle.kts`)

`./gradlew :formatter-cli:nativeCompile` (GraalVM Native Build Tools plugin,
`org.graalvm.buildtools.native`) builds a JVM-free binary. Requires a GraalVM JDK to build with
(not to run the resulting binary) — `JAVA_HOME`/`GRAALVM_HOME` pointed at one is enough; the
plugin's own toolchain detection is left off (`toolchainDetection` unset) since it can't tell a
GraalVM JDK from a plain one, and env-var detection is simpler and was verified to work with a
[mise](https://mise.jdx.dev/)-managed `graalvm-community` install.

**Load-bearing fact, found only by actually running the built binary, not by reading the build
log**: the `nativeCompile` build itself succeeds and reports a clean analysis with no fallback
warnings — but the resulting binary crashed on *every* invocation (including `--help`) with an
`ExceptionInInitializerError` out of `GroovySystem`/`MetaClassRegistryImpl`, chained from
`picocli.CommandLine$DefaultFactory.loadClosureClass()`. picocli's `DefaultFactory` unconditionally
probes for `groovy.lang.Closure` on the classpath (to support Groovy-closure-based command
factories, a feature this CLI never uses) the first time any `CommandLine` is constructed — and on
this classpath `groovy.lang.Closure` **is** present (it's `formatter-parser`'s real dependency, for
the ANTLR4 parser). Merely *loading* that class triggers `Closure`'s static initializer, which
bootstraps Groovy's entire metaclass/DGM (Default Groovy Methods) runtime — a system this
formatter has no other reason to touch, since it only walks the parser's CST. That bootstrap reads
thousands of reflectively-loaded `org.codehaus.groovy.runtime.dgm$N` classes plus a `META-INF/dgminfo`
resource, none of which `native-image`'s static analysis has any reason to see, so it fails at
runtime under the closed-world assumption. Registering that reflection config was not attempted —
it's a huge, fragile surface for a feature that's dead code here. Instead, `Main.main()` sets
`System.setProperty("picocli.disable.closures", "true")` (a real picocli option since 4.7.0, see
its javadoc) *before* the first `CommandLine` is constructed, which short-circuits the probe
entirely — `groovy.lang.Closure` is never loaded, Groovy's metaclass system is never touched, and
the binary works. **Don't remove that line** thinking it's inert — it's the fix, not a leftover.
This will resurface as the same crash if `formatter-cli` ever gains a second entry point that
constructs a `CommandLine` without going through `Main.main()`.

`formatter-cli/build.gradle.kts` also wires up `picocli-codegen` as an annotation processor
(`-Aproject=...` compiler arg) to generate picocli's own GraalVM reflect-config for the
`@Command`/`@Option`-annotated classes at compile time — this part worked without needing the
Groovy digging above; it's what lets picocli read its own annotations under native-image at all.

Smoke-test any change here by actually running the built binary against real `.groovy` files
(`--help`, `-`, a file arg, `-i`, `-r --check`, and a deliberately-unparseable file to check error
output) — the build succeeding and the analysis phase reporting no fallback is not evidence the
binary works, per the incident above.

## `formatter-shell` / `shell-format-cli` (the shell formatter)

A second, independent formatter in this repo: reformats shell (sh/bash) source, standalone (via
`shell-format-cli`) or as the engine behind a Jenkinsfile `sh '''...'''`/`sh(...)` step's script
body — see the `DocPrintingVisitor`/`tryPrintAsShellScript` bullet above for how `formatter-print`
wires this in (`formatter-print/build.gradle.kts` depends on `formatter-shell`) and how it falls
back to plain verbatim reindenting for anything the shell formatter rejects. Public API:
`dev.groovyfmt.shell.ShellFormatter.format(String)` / `format(String, RenderOptions)`.

**Unlike the Groovy formatter, there is no off-the-shelf grammar to reuse.** Groovy's own ANTLR4
grammar being embedded in its compiler jar is what let `formatter-parser` avoid writing a Groovy
grammar from scratch; no equivalent exists for shell as a Java dependency, so `formatter-shell`
hand-writes a lexer (`lexer/Lexer.java`) and a recursive-descent parser (`parser/Parser.java`)
implementing a practical POSIX-sh-plus-common-bash subset, feeding an AST (`ast/*.java`) that
`print/ShellPrinter.java` walks to build a `Doc` tree — reusing `formatter-doc` unchanged, since
it has zero Groovy-specific (or shell-specific) knowledge.

### Design choices specific to shell

- **Words are split only at quote/expansion boundaries, never re-interpreted.** A `Literal`
  `WordPart` holds the exact source substring (backslash escapes included) for an unquoted run;
  quoted bodies and expansion inner text (`${...}`, `` $(...) ``, `` `...` ``, `$((...))`) are
  likewise stored raw and printed back verbatim. This is the same philosophy `formatter-print`
  uses for GString bodies — a structural formatter, not a content normalizer — and it's what
  keeps the lexer's escaping logic simple and correct by construction rather than needing to
  round-trip through an interpreted-then-re-escaped representation.
- **`$(...)` command substitutions ARE recursively reformatted** (parsed and printed through the
  same `Parser`/`ShellPrinter`, falling back to printing the raw text verbatim if the nested parse
  fails) — this is the one place content actually gets reformatted, since it's genuinely nested
  shell syntax, not opaque data. Backtick `` `...` `` substitutions are **not** reformatted
  (always printed verbatim) — their escaping rules differ from `$(...)` and re-lexing them wasn't
  worth the added complexity for a legacy form `$(...)` has mostly replaced.
- **Every statement list (function/block/loop bodies, and top-level scripts) prints one statement
  per line, always.** This sidesteps needing a flat/broken dual-mode block renderer; the one place
  that still benefits from staying on one line when short — a `$(...)` with exactly one statement,
  e.g. `$(basename "$f")` — falls out for free, since `ShellPrinter` only inserts a hardline
  *between* entries, never around a lone one.
- **Here-doc bodies are never reindented or otherwise altered, even for `<<-`.** The lexer reads a
  pending here-doc's body as soon as it crosses the newline ending the redirect's opening line
  (`Lexer.readHereDocBodies`, mirroring how real shells read here-docs), via a mutable
  `ast.HereDocBody` holder the parser creates before the body text exists and the lexer fills in
  later — but the stored `text` is always the exact original lines. `<<-`'s leading-tab stripping
  is applied only to the *comparison* used to recognize the terminator line, never to the stored
  body, so a heredoc's content can never be silently altered by this formatter.
- **Blank-line detection between statements counts NEWLINE *tokens*, not source line-number
  deltas** (`Parser.consumeBoundary`). A here-doc's opening redirect is followed by exactly one
  NEWLINE token even though the lexer silently swallows several physical source lines (the body
  plus the delimiter line) while producing it; comparing raw line numbers there previously
  produced a false blank-line after every here-doc (a real bug, found by the idempotency test —
  same lesson as the Groovy side: **run it, don't just reason about it**). One NEWLINE token is
  always "free" (it ends the previous statement's own line, or the block-opening keyword's line
  on a list's first call); each comment consumes one more (it sits on its own line); anything
  beyond that is a real blank line.
- **Comments and blank lines are preserved, capped at one blank line, same as the Groovy side** —
  see `ast.StatementList`/`Comment` and `Parser.consumeBoundary`/`parseStatementList`. There's no
  ANTLR hidden-channel to lean on here (there's no ANTLR at all); comments are recorded by the
  lexer with their line number as it scans past them and matched up against statement boundaries
  by the parser afterward.

### What's out of scope (throws `UnsupportedOperationException` or a parse error, never silently mis-formatted)

Array assignments (`arr=(a b c)`), C-style `for ((;;))`, arithmetic commands `((...))` as a
standalone command, `[[ ... ]]` extended-test internals (parsed as an ordinary simple command —
works for the common case, but `&&`/`||`/`<`/`>` *inside* `[[ ]]` will be misparsed as pipeline/
redirection operators since there's no special lexer mode for it), process substitution
(`<(...)`/`>(...)`), brace expansion, and any parameter-expansion operator inside `${...}` (stored
and printed verbatim, never parsed). Long `&&`/`||` chains and long pipelines are never
line-wrapped (printed on one line regardless of length) — no width-based flex logic was built for
them, unlike the Groovy side's argument-list wrapping.

### Testing

`formatter-shell`'s `ShellFormatterTest` covers each construct plus a `formattingIsIdempotent`
parameterized test, the same pattern as the Groovy side. As with the Groovy formatter, treat
idempotency failures as the highest-signal check when something looks subtly wrong — it's what
caught the here-doc blank-line bug above.

### Build / run

```bash
./gradlew :shell-format-cli:run --args="path/to/script.sh"        # format one file, prints to stdout
./gradlew :shell-format-cli:run --args="-i path/to/script.sh"     # format in place
./gradlew :shell-format-cli:nativeCompile                          # JVM-free binary (GraalVM required to build)
```

`shell-format-cli/build.gradle.kts` mirrors `formatter-cli/build.gradle.kts` (same picocli setup,
same fat-jar task, same GraalVM Native Build Tools config) but has **no dependency on Groovy at
all** — so the `groovy.lang.Closure`/picocli-probe crash documented above for `formatter-cli`
doesn't apply here; `shell-format-cli`'s `Main.java` deliberately does *not* set
`picocli.disable.closures`, since there's nothing on its classpath for that probe to trip over.
Verified: `nativeCompile` produces a working binary (`--help`, a file arg, stdin, and a
deliberately-unparseable input all checked directly against the built executable, not just a
clean analysis log — same discipline as the Groovy CLI's native image, per the incident above).
