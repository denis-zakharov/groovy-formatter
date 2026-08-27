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
formatter-cli         Minimal CLI (single file -> stdout). picocli is a declared dependency but
                      not yet wired up — Main.java is hand-rolled arg parsing.
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

- 4-space indent, 100-column width (`GroovyFormatter.OPTIONS`).
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
```

Java 17 toolchain (set per-module via the root `build.gradle.kts` `subprojects {}` block). Pinned
Groovy version: `4.0.29` (see `formatter-parser/build.gradle.kts`) — bump deliberately, and
re-verify the "load-bearing facts" above (especially the shading assumption and the `*AltContext`
audit) against the new jar before trusting it.
