package dev.groovyfmt.print;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Phase 7 (long tail): labeled statements, synchronized blocks, method references/pointers
 * ({@code ::}, {@code .&}), standard (Java-style) lambdas, {@code yield}, and switch expressions
 * (arrow-style and colon-style with explicit yield).
 */
class GroovyFormatterPhase7Test {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "outer:\nfor (i in 1..3) {\n    break outer\n}\n",
                "synchronized (this) {\n    x = 1\n}\n",
                "def f = String::length\n",
                "def g = (a, b) -> a + b\n",
                "def h = x -> x * 2\n",
                "def result = switch (x) {\n    case 1 -> \"one\"\n    case 2, 3 -> \"two-or-three\"\n    default -> \"other\"\n}\n",
                "class Foo {\n    def maker = ArrayList::new\n}\n",
                "class Foo {\n    def bar() {\n        def ptr = this.&helper\n    }\n}\n",
            })
    void formattingIsIdempotent(String source) {
        String once = GroovyFormatter.format(source);
        String twice = GroovyFormatter.format(once);
        assertEquals(once, twice, "format(format(x)) must equal format(x)");
    }

    @Test
    void formatsALabeledLoopWithABreakToTheLabel() {
        String source = "outer:\nfor (i in 1..3) {\n    break outer\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsASynchronizedBlock() {
        String source = "synchronized (this) {\n    x = 1\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAMethodReferenceAndAConstructorReference() {
        String source = "class Foo {\n    def f = String::length\n    def maker = ArrayList::new\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAMethodPointer() {
        String source = "class Foo {\n    def bar() {\n        def ptr = this.&helper\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsParenthesizedAndBareSingleParamStandardLambdas() {
        String source = "def g = (a, b) -> a + b\ndef h = x -> x * 2\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAnArrowStyleSwitchExpressionWithMultiValueCaseLabels() {
        String source =
                """
                def result = switch (x) {
                    case 1 -> "one"
                    case 2, 3 -> "two-or-three"
                    default -> "other"
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAColonStyleSwitchExpressionWithExplicitYield() {
        String source =
                """
                class Foo {
                    def bar() {
                        def x = switch (y) {
                            case 1: yield y * 2
                            default: yield 0
                        }
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void breaksAnArrowCaseWhoseBodyIsABlock() {
        String source =
                """
                class Foo {
                    def bar() {
                        def x = switch (y) {
                            case 1:
                                {
                                    int z = y * 2
                                    yield z
                                }
                            default: yield 0
                        }
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }
}
