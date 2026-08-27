package dev.groovyfmt.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for bugs found by surveying the formatter against real-world corpora
 * (apache/groovy's own compiler test suite, spockframework/spock, real Gradle build scripts).
 *
 * <p>The most serious findings were {@code visitAndExprAlt}/{@code visitExclusiveOrExprAlt}/
 * {@code visitInclusiveOrExprAlt} ({@code &}, {@code ^}, {@code |}) having no visitor override at
 * all — ANTLR's default {@code visitChildren} silently discarded the left operand and operator,
 * keeping only the right operand, with no exception. A systematic audit of every ANTLR
 * labeled-alternative ("Alt") context class turned up three more of the same risk (power {@code
 * **}, regex {@code =~}/{@code ==~}, explicit casts) which were simply never exercised by any
 * hand-written test.
 */
class GroovyFormatterCorpusFixesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "class Foo {\n    def bar() {\n        def x = a | b\n        def y = a & b\n        def z = a ^ b\n    }\n}\n",
                "class Foo {\n    def bar(Object o) {\n        if (o instanceof String) {\n            return o as String\n        }\n    }\n}\n",
                "class Foo {\n    def c = { -> 1 }\n}\n",
                "class Foo {\n    Foo(MetaClassRegistryVeryLongTypeName metaClassRegistry, ClassSomethingLong aClass, MetaClassAnotherLongOne adaptee) {}\n}\n",
                "class Foo {\n    def bar() {\n        def a = 2 ** 10\n        def m = \"hello\" =~ /h.*o/\n        def n = \"hello\" ==~ /hello/\n        def x = (String) obj\n    }\n}\n",
                "def x = a?.b\ndef y = a??.b\n",
            })
    void formattingIsIdempotent(String source) {
        String once = GroovyFormatter.format(source);
        String twice = GroovyFormatter.format(once);
        assertEquals(once, twice, "format(format(x)) must equal format(x)");
    }

    @Test
    void doesNotSilentlyDropTheLeftOperandOfBitwiseAndOrXor() {
        String source =
                """
                class Foo {
                    def bar() {
                        def x = a | b
                        def y = a & b
                        def z = a ^ b
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsInstanceofAndAsTypeExpressions() {
        String source =
                """
                class Foo {
                    def bar(Object o) {
                        if (o instanceof String) {
                            return o as String
                        }
                        if (!(o instanceof Integer)) {
                            return null
                        }
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void doesNotDoubleSpaceBeforeArrowOnAZeroParamClosure() {
        String source = "class Foo {\n    def c = { -> 1 }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void doesNotAddATrailingCommaToAWrappedFormalParameterList() {
        // Groovy's grammar does not accept a trailing comma in a formal parameter list — unlike
        // argument lists / list / map literals, which do (and must keep it). A trailing comma
        // here makes the output fail to re-parse; the idempotency check below is what would catch
        // a regression (first-pass output looks fine, re-parsing it is what fails).
        String source =
                """
                class Foo {
                    Foo(
                        MetaClassRegistryVeryLongTypeName metaClassRegistry,
                        ClassSomethingLong aClass,
                        MetaClassAnotherLongOne adaptee
                    ) {}
                }
                """;
        String formatted = GroovyFormatter.format(source);
        assertEquals(source, formatted);
        String reformatted = GroovyFormatter.format(formatted); // must not throw on re-parse
        assertEquals(formatted, reformatted);
    }

    @Test
    void formatsPowerRegexFindRegexMatchAndExplicitCastExpressions() {
        String source =
                """
                class Foo {
                    def bar() {
                        def a = 2 ** 10
                        def m = "hello" =~ /h.*o/
                        def n = "hello" ==~ /hello/
                        def x = (String) obj
                        def y = (int) 3.5
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsSafeNavigationAndSafeChainDotOperators() {
        // The safe-chain-dot connector was previously mis-printed as "?.." (invalid Groovy syntax)
        // instead of "??.", silently corrupting valid source.
        String source = "def x = a?.b\ndef y = a??.b\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void throwsAClearErrorForDefLessMultipleAssignmentRatherThanMisformatting() {
        String source = "class Foo {\n  def bar() {\n    def x\n    def y\n    (x, y) = [1, 2]\n  }\n}\n";
        UnsupportedOperationException e =
                assertThrows(UnsupportedOperationException.class, () -> GroovyFormatter.format(source));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("tuple destructuring"));
    }
}
