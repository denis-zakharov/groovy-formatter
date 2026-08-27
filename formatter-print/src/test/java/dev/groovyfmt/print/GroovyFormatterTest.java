package dev.groovyfmt.print;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GroovyFormatterTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "class Foo {}\n",
                "package com.example\n\nclass Foo {}\n",
                "package com.example\n\nimport java.util.List\nimport static java.lang.Math.max\n\nclass Foo {}\n",
                """
                class Foo {
                    int x
                    String name = "hi"

                    def bar() {
                        return x
                    }
                }
                """,
                """
                class Calc {
                    private final int base

                    public static int compute(int a, int b) {
                        return max(a, b) + base
                    }
                }
                """,
                """
                class Foo {
                    def bar() {
                        int x = 1
                        x = x + 2 * 3
                        foo.baz(1, "a")
                        qux()
                        if (x > 0) {
                            return x
                        } else {
                            return 0
                        }
                    }
                }
                """,
                "class Foo {}\nclass Bar {}\n",
            })
    void formattingIsIdempotent(String source) {
        String once = GroovyFormatter.format(source);
        String twice = GroovyFormatter.format(once);
        assertEquals(once, twice, "format(format(x)) must equal format(x)");
    }

    @Test
    void formatsAnEmptyClass() {
        assertEquals("class Foo {}\n", GroovyFormatter.format("class Foo{}"));
    }

    @Test
    void formatsPackageAndImportsWithBlankLineSeparation() {
        String source = "package com.example\nimport java.util.List\nclass Foo {}\n";
        String expected = "package com.example\n\nimport java.util.List\n\nclass Foo {}\n";
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void formatsStaticAndWildcardImports() {
        String source = "import static java.lang.Math.max\nimport java.util.*\nclass Foo {}\n";
        String expected = "import static java.lang.Math.max\nimport java.util.*\n\nclass Foo {}\n";
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void formatsFieldsAndAMethodWithReturn() {
        String source =
                """
                class Foo {
                  int x
                  String name="hi"
                  def bar(){
                  return x
                  }
                }
                """;
        String expected =
                """
                class Foo {
                    int x

                    String name = "hi"

                    def bar() {
                        return x
                    }
                }
                """;
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void formatsIfElseAssignmentAndDottedMethodCalls() {
        String source =
                """
                class Foo {
                  def bar() {
                    int x=1
                    x=x+2*3
                    foo.baz(1,"a")
                    qux()
                    if(x>0){
                      return x
                    }else{
                      return 0
                    }
                  }
                }
                """;
        String expected =
                """
                class Foo {
                    def bar() {
                        int x = 1
                        x = x + 2 * 3
                        foo.baz(1, "a")
                        qux()
                        if (x > 0) {
                            return x
                        } else {
                            return 0
                        }
                    }
                }
                """;
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void throwsAClearErrorForConstructsOutsideThePhase2Subset() {
        // Command-chain expressions (Spock/Gradle/Jenkins-style) are explicitly out of scope
        // until Phase 4.
        String source = "class Foo {\n  def bar() {\n    foo bar: 1\n  }\n}\n";
        UnsupportedOperationException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        UnsupportedOperationException.class, () -> GroovyFormatter.format(source));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("command-chain"));
    }
}
