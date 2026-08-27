package dev.groovyfmt.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Phase 5: the type-system surface — generics (classes/methods/types/new-expressions, bounds,
 * wildcards, diamond), extends/implements, annotations (as declaration modifiers, with own-line
 * placement, and as element values), varargs, throws clauses, interfaces, traits, enums (with
 * constants and bodies), and records.
 */
class GroovyFormatterPhase5Test {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "class Box<T extends Comparable<T>> extends AbstractBox<T> implements Comparable<T>, Serializable {\n    T value\n}\n",
                "@Deprecated\nclass Foo {\n}\n",
                "class Foo {\n    @Override\n    int bar() {\n        return 0\n    }\n}\n",
                "class Foo {\n    static <U> U identity(U x) {\n        return x\n    }\n}\n",
                "class Foo {\n    void risky() throws IOException, RuntimeException {\n        return\n    }\n}\n",
                "class Foo {\n    void varargs(String first, String... rest) {}\n}\n",
                "interface Shape {\n    double area();\n}\n",
                "trait Named {\n    String name\n}\n",
                "enum Color {\n    RED, GREEN, BLUE\n}\n",
                "enum Planet {\n    MERCURY(3.3), VENUS(4.8)\n\n    final double mass\n}\n",
                "record Point(int x, int y) {\n}\n",
                "class Foo {\n    def make() {\n        List<String> l = new ArrayList<>()\n        Map<String, Integer> m = new HashMap<String, Integer>()\n    }\n}\n",
            })
    void formattingIsIdempotent(String source) {
        String once = GroovyFormatter.format(source);
        String twice = GroovyFormatter.format(once);
        assertEquals(once, twice, "format(format(x)) must equal format(x)");
    }

    @Test
    void formatsGenericClassWithBoundsExtendsAndImplements() {
        String source =
                "class Box<T extends Comparable<T>> extends AbstractBox<T> implements Comparable<T>, Serializable {\n"
                        + "    T value\n"
                        + "}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void putsAClassLevelAnnotationOnItsOwnLine() {
        String source = "@Deprecated\nclass Foo {}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void putsAMethodLevelAnnotationOnItsOwnLineSeparateFromOtherModifiers() {
        String source =
                """
                class Foo {
                    @Override
                    public int bar() {
                        return 0
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void keepsAParameterLevelAnnotationInline() {
        // Unlike declaration-level annotations, a parameter annotation conventionally stays on
        // the same line as the parameter (e.g. Spring/Lombok-style `@NotNull String x`).
        String source = "class Foo {\n    void bar(@NotNull String x) {}\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAnAnnotationWithElementValues() {
        String source = "class Foo {\n    @RequestMapping(value = \"/x\", method = \"GET\")\n    void bar() {}\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAGenericMethodWithTypeParameters() {
        String source = "class Foo {\n    static <U> U identity(U x) {\n        return x\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAThrowsClauseWithMultipleExceptionTypes() {
        String source =
                "class Foo {\n    void risky() throws IOException, RuntimeException {\n        return\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsVarargsParameters() {
        String source = "class Foo {\n    void bar(String first, String... rest) {}\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAnInterfaceWithAnAbstractMethod() {
        String source = "interface Shape {\n    double area();\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsATraitWithAField() {
        String source = "trait Named {\n    String name\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsASimpleEnum() {
        String source = "enum Color {\n    RED, GREEN, BLUE\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsAnEnumWithConstructorArgumentsAndAdditionalMembers() {
        String source =
                """
                enum Planet {
                    MERCURY(3.3), VENUS(4.8)

                    final double mass

                    Planet(double mass) {
                        this.mass = mass
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsARecordDeclaration() {
        String source = "record Point(int x, int y) {}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsDiamondAndExplicitGenericTypeArgumentsOnNewExpressions() {
        String source =
                """
                class Foo {
                    def make() {
                        List<String> l = new ArrayList<>()
                        Map<String, Integer> m = new HashMap<String, Integer>()
                        List<? extends Number> nums = l
                        List<? super Integer> sink = l
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formatsANestedClassWithModifiers() {
        String source = "class Outer {\n    private static class Inner {\n        int x\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void throwsAClearErrorForSealedPermitsRatherThanCrashing() {
        String source = "sealed class Shape permits Circle, Square {\n}\n";
        assertThrows(UnsupportedOperationException.class, () -> GroovyFormatter.format(source));
    }

    @Test
    void throwsAClearErrorForCompactConstructorsRatherThanCrashing() {
        String source = "record Point(int x, int y) {\n    Point {\n    }\n}\n";
        assertThrows(UnsupportedOperationException.class, () -> GroovyFormatter.format(source));
    }
}
