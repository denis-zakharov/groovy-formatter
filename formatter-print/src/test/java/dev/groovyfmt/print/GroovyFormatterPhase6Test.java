package dev.groovyfmt.print;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Phase 6: script-mode top-level code — statements and method declarations directly at
 * compilation-unit level (no enclosing class), and shebang-line preservation. This is a genuinely
 * distinct grammar/AST path from a method body, not "just like a block" — Phase 2 through 5 only
 * ever printed statements nested inside a class's methods.
 */
class GroovyFormatterPhase6Test {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "def name = \"world\"\nprintln name\n",
                "int add(int a, int b) {\n    return a + b\n}\n\nprintln add(1, 2)\n",
                "def total = 0\nfor (i in 1..5) {\n    total += i\n}\n",
                "#!/usr/bin/env groovy\nprintln \"hi\"\n",
                "class Helper {\n    static int square(int x) {\n        return x * x\n    }\n}\n\nprintln Helper.square(4)\n",
            })
    void formattingIsIdempotent(String source) {
        String once = GroovyFormatter.format(source);
        String twice = GroovyFormatter.format(once);
        assertEquals(once, twice, "format(format(x)) must equal format(x)");
    }

    @Test
    void formatsTopLevelStatementsMethodsAndAClassTogether() {
        String source =
                """
                import java.util.List

                def name = "world"
                println "hello ${name}"

                int add(int a, int b) {
                    return a + b
                }

                def total = 0
                for (i in 1..5) {
                    total += i
                }

                if (total > 10) {
                    println "big"
                } else {
                    println "small"
                }

                class Helper {
                    static int square(int x) {
                        return x * x
                    }
                }

                println Helper.square(4)
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void preservesAShebangLineVerbatimAtTheStartOfTheFile() {
        String source = "#!/usr/bin/env groovy\nprintln \"hi\"\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void preservesCommentsAroundTopLevelScriptStatements() {
        String source =
                """
                // entry point
                def name = "world"

                /**
                 * Says hello.
                 */
                def greet() {
                    println "hello ${name}"
                }

                greet() // call it
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void doesNotInventABlankLineBetweenTheShebangAndTheFirstStatement() {
        String source = "#!/usr/bin/env groovy\ndef x = 1\nprintln x\n";
        assertEquals(source, GroovyFormatter.format(source));
    }
}
