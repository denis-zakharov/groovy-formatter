package dev.groovyfmt.print;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Triple-quoted (single- and double-quoted) multi-line strings are printed verbatim except for
 * their own indentation, which is shifted to track the nesting depth of the statement/expression
 * they're part of — see {@code Doc.IndentedVerbatim}. The primary motivating case is a Jenkinsfile
 * {@code sh '''...'''} step nested inside {@code pipeline { stages { stage { steps { ... } } } } }.
 */
class GroovyFormatterMultilineStringTest {

    @Test
    void reindentsATripleQuotedStringBodyToMatchItsNewNestingDepth() {
        // Source is under-indented for how deeply "sh" is actually nested; the formatter both
        // fixes the surrounding structure's indentation and shifts the string body by the same
        // delta, preserving the string's own *relative* indentation (echo/ls stay one level
        // deeper than the closing '''; ls stays aligned with echo).
        String source =
                """
                pipeline {
                stages {
                stage('Build') {
                steps {
                    sh '''
                        echo hello
                        ls -la
                    '''
                }
                }
                }
                }
                """;
        String expected =
                """
                pipeline {
                    stages {
                        stage('Build') {
                            steps {
                                sh '''
                                    echo hello
                                    ls -la
                                '''
                            }
                        }
                    }
                }
                """;
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void leavesAnAlreadyCorrectlyIndentedTripleQuotedStringUnchanged() {
        String source =
                """
                pipeline {
                    stages {
                        stage('Build') {
                            steps {
                                sh '''
                                    echo hello
                                    ls -la
                                '''
                            }
                        }
                    }
                }
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formattingATripleQuotedStringIsIdempotent() {
        String source =
                """
                pipeline {
                stages {
                stage('Build') {
                steps {
                    sh '''
                        echo hello
                        ls -la
                    '''
                }
                }
                }
                }
                """;
        String once = GroovyFormatter.format(source);
        String twice = GroovyFormatter.format(once);
        assertEquals(once, twice, "format(format(x)) must equal format(x)");
    }

    @Test
    void reallyReformatsTheShellBodyOfAShStep() {
        // Not just reindented: the shell body itself gets normalized (collapsed extra whitespace,
        // "&&" spacing, pipeline spacing) by the real shell formatter.
        String source =
                """
                pipeline {
                    stages {
                        stage('Build') {
                            steps {
                                sh '''
                                    make build&&make test
                                    cat  foo.txt|grep bar
                                '''
                            }
                        }
                    }
                }
                """;
        String expected =
                """
                pipeline {
                    stages {
                        stage('Build') {
                            steps {
                                sh '''
                                    make build && make test
                                    cat foo.txt | grep bar
                                '''
                            }
                        }
                    }
                }
                """;
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void reallyReformatsTheShellBodyOfAParenthesizedShStep() {
        // A multiline literal always forces its enclosing group to break (Doc.IndentedVerbatim),
        // so sh(...)'s argument list breaks onto its own lines too, same as it already did before
        // this string got real shell formatting — that part isn't new here.
        String source =
                """
                pipeline {
                    stages {
                        stage('Build') {
                            steps {
                                sh('''
                                    make build&&make test
                                ''')
                            }
                        }
                    }
                }
                """;
        String expected =
                """
                pipeline {
                    stages {
                        stage('Build') {
                            steps {
                                sh(
                                    '''
                                        make build && make test
                                    ''',
                                )
                            }
                        }
                    }
                }
                """;
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void fallsBackToVerbatimReindentWhenShBodyIsNotValidShell() {
        // arr=(a b c) is deliberately unsupported by the shell formatter (see
        // ShellFormatterTest#arrayAssignmentIsUnsupported); the Groovy formatter must not fail the
        // whole file over it, just leave that sh body reindented verbatim like before.
        String source =
                """
                pipeline {
                stages {
                stage('Build') {
                steps {
                    sh '''
                        arr=(a b c)
                    '''
                }
                }
                }
                }
                """;
        String expected =
                """
                pipeline {
                    stages {
                        stage('Build') {
                            steps {
                                sh '''
                                    arr=(a b c)
                                '''
                            }
                        }
                    }
                }
                """;
        assertEquals(expected, GroovyFormatter.format(source));
    }

    @Test
    void doesNotShellFormatTripleQuotedStringsUnrelatedToShSteps() {
        String source =
                """
                def script = '''
                    make build&&make test
                '''
                """;
        assertEquals(source, GroovyFormatter.format(source));
    }

    @Test
    void formattingAShStepShellBodyIsIdempotent() {
        String source =
                """
                pipeline {
                stages {
                stage('Build') {
                steps {
                    sh '''
                        make build&&make test
                        cat  foo.txt|grep bar
                    '''
                }
                }
                }
                }
                """;
        String once = GroovyFormatter.format(source);
        String twice = GroovyFormatter.format(once);
        assertEquals(once, twice, "format(format(x)) must equal format(x)");
    }

    @Test
    void preservesContentOfATripleQuotedGString() {
        String source =
                """
                def name = "world"
                pipeline {
                stages {
                stage('Build') {
                steps {
                    sh \"\"\"
                        echo hello ${name}
                    \"\"\"
                }
                }
                }
                }
                """;
        String expected =
                """
                def name = "world"
                pipeline {
                    stages {
                        stage('Build') {
                            steps {
                                sh \"\"\"
                                    echo hello ${name}
                                \"\"\"
                            }
                        }
                    }
                }
                """;
        assertEquals(expected, GroovyFormatter.format(source));
    }
}
