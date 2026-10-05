package dev.groovyfmt.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Phase 4: loops, try/catch/finally, switch, break/continue/throw/assert, ternary/elvis, ranges,
 * index access, closures, GString verbatim preservation, 'new' expressions, list/map literals.
 */
class GroovyFormatterPhase4Test {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "class Foo {\n    def bar() {\n        for (int i = 0; i < 10; i++) {\n            x = i\n        }\n    }\n}\n",
                "class Foo {\n    def bar() {\n        for (item in list) {\n            y = item\n        }\n    }\n}\n",
                "class Foo {\n    def bar() {\n        while (x > 0) {\n            x--\n        }\n    }\n}\n",
                "class Foo {\n    def bar() {\n        do {\n            x++\n        } while (x < 10)\n    }\n}\n",
                "class Foo {\n    def bar() {\n        try {\n            foo()\n        } catch (IOException e) {\n            bar()\n        } finally {\n            baz()\n        }\n    }\n}\n",
                "class Foo {\n    def bar() {\n        switch (x) {\n            case 1:\n                y = 1\n                break\n            default:\n                y = 0\n        }\n    }\n}\n",
                "class Foo {\n    def bar() {\n        def r = 1..10\n        def s = list[0]\n        def t = a ?: b\n        def u = a ? b : c\n        def v = list*.name\n    }\n}\n",
                "class Foo {\n    def bar() {\n        list.each { it * 2 }\n        def cl = { a, b -> a + b }\n    }\n}\n",
                "class Foo {\n    def bar() {\n        def gs = \"hi ${name}, you are ${age + 1}\"\n    }\n}\n",
                "class Foo {\n    def bar() {\n        def e = new RuntimeException(\"x\")\n    }\n}\n",
                "class Foo {\n    def bar() {\n        def l = [1, 2, 3]\n        def m = [a: 1, b: 2]\n    }\n}\n",
            })
    void formattingIsIdempotent(String source) {
        String once = GroovyFormatter.format(source);
        String twice = GroovyFormatter.format(once);
        assertEquals(once, twice, "format(format(x)) must equal format(x)");
    }

    @Test
    void formatsAllLoopForms() {
        String source =
                """
                class Foo {
                    def loops() {
                        for (int i = 0; i < 10; i++) {
                            x = i
                        }
                        for (item in list) {
                            y = item
                        }
                        while (x > 0) {
                            x--
                        }
                        do {
                            x++
                        } while (x < 10)
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsTryCatchFinally() {
        String source =
                """
                class Foo {
                    def bar() {
                        try {
                            foo()
                        } catch (IOException e) {
                            bar()
                        } finally {
                            baz()
                        }
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsASwitchStatement() {
        String source =
                """
                class Foo {
                    def bar() {
                        switch (x) {
                            case 1:
                                y = 1
                                break
                            default:
                                y = 0
                        }
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsBreakContinueThrowAsBareStatementsAfterAnUnbracedIf() {
        String source =
                """
                class Foo {
                    def bar() {
                        if (cond) break
                        if (cond) continue
                        if (cond) throw new RuntimeException("x")
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void preservesAssertsColonAndCommaSeparatorsDistinctly() {
        String source =
                """
                class Foo {
                    def bar() {
                        assert x > 0
                        assert x > 0 : "must be positive"
                        assert x > 0, "must be positive"
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsRangeElvisTernarySafeNavAndIndexAccessWithoutInventingSpaces() {
        String source =
                """
                class Foo {
                    def bar() {
                        def r = 1..10
                        def s = list[0]
                        def t = a ?: b
                        def u = a ? b : c
                        def v = list*.name
                        def w = list?[0]
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void keepsASingleStatementClosureOnOneLineButBreaksMultiStatementClosures() {
        String source =
                """
                class Foo {
                    def bar() {
                        list.each { it * 2 }
                        def cl = { a, b -> a + b }
                        def multi = {
                            x = 1
                            y = 2
                        }
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void doesNotAddADefKeywordToUntypedClosureOrMethodParameters() {
        // Regression: an untyped parameter used to get an incorrectly-inserted 'def' prefix,
        // since Groovy formal parameters (unlike local variable declarations) never use 'def' as
        // a type placeholder.
        String source = "class Foo {\n    def bar(x, y) {\n        return x + y\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void preservesGStringInterpolationVerbatimIncludingInternalSpacing() {
        // GStrings are printed verbatim from the raw source, not reformatted — including
        // whatever internal spacing the interpolated expression originally had.
        String source = "class Foo {\n    def bar() {\n        def gs = \"hi ${name}, you are ${ age + 1 }\"\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsANewExpressionWithArguments() {
        String source = "class Foo {\n    def bar() {\n        def e = new RuntimeException(\"x\")\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsListAndMapLiterals() {
        String source =
                """
                class Foo {
                    def bar() {
                        def l = [1, 2, 3]
                        def m = [a: 1, b: 2]
                        def emptyList = []
                        def emptyMap = [:]
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsSpreadArgumentsAndParenLessNamedArguments() {
        String source =
                """
                class Foo {
                    def bar() {
                        def spread = foo(*args)
                        foo bar: 1, baz: 2
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsCollectionLeftShiftAppend() {
        // '<<' is idiomatic Groovy for collection append (List.leftShift) and is lexed as two
        // adjacent '<' tokens, not a single shift token — easy to get wrong.
        String source = "class Foo {\n    def bar() {\n        result << clamp(item)\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsRightShiftAndUnsignedRightShift() {
        String source = "class Foo {\n    def bar() {\n        x = a >> b\n        y = a >>> b\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAMultiWordCommandChain() {
        String source = "def x() {\n    foo bar baz\n}\n";
        assertEquals(source, GroovyFormatter.format("def x() {\n  foo bar   baz\n}\n"));
    }

    @Test
    void formatsTryWithResources() {
        String source = "def bar() {\n    try (def x = open(); y) {\n        use(x)\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAnonymousInnerClasses() {
        String source = "def r = new Runnable() {\n    void run() {}\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }
}
