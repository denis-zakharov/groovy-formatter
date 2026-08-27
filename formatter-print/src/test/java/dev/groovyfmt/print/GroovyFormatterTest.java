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
    void preservesABlankLineBetweenPackageAndImportsWhenSourceHasOne() {
        String source = "package com.example\n\nimport java.util.List\n\nclass Foo {}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void doesNotInventABlankLineBetweenPackageAndImportsWhenSourceHasNone() {
        String source = "package com.example\nimport java.util.List\nclass Foo {}\n";
        String expected = "package com.example\nimport java.util.List\nclass Foo {}\n";
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void formatsStaticAndWildcardImports() {
        String source = "import static java.lang.Math.max\nimport java.util.*\nclass Foo {}\n";
        String expected = "import static java.lang.Math.max\nimport java.util.*\nclass Foo {}\n";
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void formatsFieldsAndAMethodWithReturnWithoutInventingBlankLines() {
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
    void preservesBlankLinesBetweenClassMembersWhenSourceHasThem() {
        String source =
                """
                class Foo {
                    int x

                    String name = "hi"


                    def bar() {
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
        assertEquals(expected, GroovyFormatter.format(source), "consecutive blank lines must cap at one");
    }

    @Test
    void preservesALeadingGroovydocCommentBeforeAMethod() {
        String source =
                """
                class Foo {
                    /**
                     * Computes the answer.
                     */
                    def bar() {
                        return 1
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void preservesALeadingLineCommentBeforeAField() {
        String source =
                """
                class Foo {
                    // the base value
                    int x
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void preservesATrailingSameLineCommentOnAField() {
        String source = "class Foo {\n    int x // the base value\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void preservesBlankLinesAndACommentBetweenBlockStatements() {
        String source =
                """
                class Foo {
                    def bar() {
                        int x = 1

                        // compute the result
                        return x
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void preservesADanglingCommentInAnOtherwiseEmptyClassBody() {
        String source = "class Foo {\n    // TODO: implement\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void throwsAClearErrorForACommentInsideAStatementInsteadOfMisplacingIt() {
        String source = "class Foo {\n    def bar() {\n        int x = foo(/* nested */ 1)\n    }\n}\n";
        UnsupportedOperationException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        UnsupportedOperationException.class, () -> GroovyFormatter.format(source));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("nested"));
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
    void formatsAParenLessNamedArgumentCommandExpression() {
        String source = "class Foo {\n    def bar() {\n        foo bar: 1, baz: 2\n    }\n}\n";
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void throwsAClearErrorForAMultiWordCommandChainStillOutOfScope() {
        // 'foo bar baz' (no comma, no colon) is the true multi-word command chain — a distinct,
        // more elaborate grammar path (commandArgument()) than the paren-less named/positional
        // argument shorthand ('foo bar: 1' / 'foo bar, baz'), which Phase 4 does support.
        String source = "class Foo {\n  def bar() {\n    foo bar baz\n  }\n}\n";
        UnsupportedOperationException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        UnsupportedOperationException.class, () -> GroovyFormatter.format(source));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("multi-word command-chain"));
    }
}
