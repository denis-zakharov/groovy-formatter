# groovy-formatter

An AST-aware code formatter for Groovy — it reformats your source to a single, consistent style
by actually parsing it with Groovy's own compiler grammar, not by pattern-matching lines of text.

**Status: early / in development.** It handles a substantial majority of everyday Groovy syntax
(see "What's supported" below), but it is not a complete implementation of the language yet, and
the CLI is minimal. If it hits something it doesn't understand, it stops with a clear error
rather than guessing — see "How it fails" below.

## Why

Existing Groovy formatting tools are largely line/regex-based. This one parses your file with the
same ANTLR4 grammar Groovy's own compiler uses, builds a proper pretty-printing document from the
parse tree, and renders that back out — the same general approach as tools like
`google-java-format`, `ktfmt`, or Prettier.

## Requirements

- JDK 17 or newer to build and run.
- No installation of Groovy itself is required — the formatter depends on the Groovy compiler
  library directly (as a plain jar dependency, pulled automatically by Gradle) purely for its
  parser; it doesn't shell out to `groovy` or `groovyc`.

## Building

```bash
git clone <this repo>
cd groovy-formatter
./gradlew build
```

This compiles every module and runs the full test suite. No separate install step is needed to
try the CLI — see below.

## Usage

Format a single file and print the result to stdout:

```bash
./gradlew :formatter-cli:run --args="path/to/YourFile.groovy"
```

To write the formatted output back to a file yourself:

```bash
./gradlew :formatter-cli:run --args="path/to/YourFile.groovy" --quiet > YourFile.formatted.groovy
```

There is currently no `--in-place`, `--check`, or multi-file/glob support, and no standalone
distributable binary published — invoking through `./gradlew ... run` (or building/running the
`formatter-cli` module's own jar directly) is the only supported entry point right now.

### Example

Input:

```groovy
package com.example.demo

import java.util.List

/**
 * A small calculator.
 */
class Calculator {
  private final int base

  Calculator(int base) {
    this.base=base
  }

  int add(int a,int b) {
    return a+b
  }

  def describe() {
    if(base>0){
      return "positive"
    }else{
      return "non-positive"
    }
  }
}
```

Output:

```groovy
package com.example.demo

import java.util.List

/**
 * A small calculator.
 */
class Calculator {
    private final int base

    Calculator(int base) {
        this.base = base
    }

    int add(int a, int b) {
        return a + b
    }

    def describe() {
        if (base > 0) {
            return "positive"
        } else {
            return "non-positive"
        }
    }
}
```

(4-space indentation, consistent spacing, and structure normalized; comments, Groovydoc, and
blank-line grouping between statements/members are preserved from the original — up to one blank
line between items, never invented where the source had none.)

## What's supported

- Classes, interfaces, traits, enums (including constants with constructor arguments and
  additional members), and records.
- Generics: class/method type parameters with bounds, type arguments (including wildcards and
  diamond `<>`), `extends`/`implements`.
- Annotations, including element values (`@RequestMapping(value = "/x")`) — on their own line for
  class/method/field declarations, inline for parameters, matching each construct's own
  convention.
- All the usual control flow: `if`/`else`, all loop forms, `try`/`catch`/`finally`,
  `switch` as both a statement and an expression (arrow-style and colon-style with `yield`),
  labeled statements, `synchronized` blocks.
- Expressions: the full standard operator set (arithmetic, bitwise, logical, relational,
  `instanceof`/`as`, elvis `?:`, ternary, ranges, spread, safe navigation, casts), closures
  (single-statement ones stay on one line when they fit), Java-style lambdas, method
  references/pointers (`Foo::bar`, `this.&method`), and the paren-less named/positional-argument
  call shorthand (`foo bar: 1, baz: 2`).
- Comments (`//`, `/* */`, `/** */`) and blank-line grouping between statements and class
  members, preserved from the source.
- GStrings (interpolated strings) — preserved exactly as written, including the interpolated
  expressions; the formatter never rewrites what's inside a string.
- Script-mode files (top-level statements/methods, no enclosing class) and shebang lines.

## What's not supported yet

The formatter will refuse to format a file containing any of these, with a message describing
what it hit, rather than producing incorrect output:

- Multi-word command chains (`foo bar baz`, without a comma or colon).
- try-with-resources, anonymous inner class bodies, array creation expressions (`new int[5]`).
- `sealed`/`permits`, record compact constructors, annotation type declarations (`@interface`).
- Parameter default values, multiple-assignment/tuple destructuring (`def (a, b) = [...]`).
- A comment written inside a single expression or statement (comments *between* statements or
  class members are fully supported).
- Explicit generic type witnesses on method calls (`Collections.<String>emptyList()`).

## How it fails

If a file uses a construct outside the current supported subset, or genuinely isn't valid Groovy,
the formatter stops and reports why rather than emitting partial, silently-wrong, or corrupted
output. This is a deliberate design principle, not an oversight — see `AGENTS.md` for the reasoning
and the bugs that motivated it.

## Project layout

Six Gradle modules under one build; see `AGENTS.md` for the reasoning behind the module
boundaries, the architecture of the pretty-printing engine, and notes for anyone extending
language coverage.

```
formatter-doc        The pretty-printing engine (Wadler/Prettier-style), Groovy-agnostic.
formatter-parser      Thin wrapper over Groovy's own ANTLR4 parser.
formatter-comments    Comment and blank-line extraction/attachment.
formatter-print       The actual formatter: walks the parse tree, builds the output.
formatter-cli         The `groovy-format` command-line entry point.
formatter-testkit     Scaffolded for a future real-world regression corpus (not yet built out).
```

## Contributing

See `AGENTS.md` for architectural decisions, the investigation methodology used to add new
grammar coverage, and the testing/verification discipline the project follows (in short:
idempotency — `format(format(x)) == format(x)` — is the cheapest and highest-signal check, and
every real bug found so far was found by running the formatter against real code, not by
reasoning about the printer in the abstract).
