package dev.groovyfmt.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovyjarjarantlr4.v4.runtime.CommonTokenStream;
import groovyjarjarantlr4.v4.runtime.Token;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Canary tests for the load-bearing assumptions the whole project depends on: that Groovy's own
 * shaded ANTLR4 parser can be driven directly, and that comments must be found by token text
 * (never token type/channel, since both {@code //} and {@code /* *}{@code /} comments are lexed
 * with their type rewritten to {@code NL}, and land on either the default or hidden channel
 * depending on paren-nesting). If a future Groovy version bump breaks either assumption, these
 * tests should fail loudly here rather than surfacing as a mysterious error deep in the printer.
 */
class GroovyCstParserCanaryTest {

    @Test
    void parsesATrivialClassDeclarationUsingGroovysOwnShadedAntlrRuntime() {
        ParsedSource parsed = GroovyCstParser.parse("class Foo {}\n");

        assertEquals("CompilationUnitContext", parsed.compilationUnit().getClass().getSimpleName());
        assertTrue(parsed.compilationUnit().getChildCount() > 0);
    }

    @Test
    void findsALineCommentByTokenTextRegardlessOfTypeOrChannel() {
        String source = "class Foo {\n  // a comment\n  int x\n}\n";
        ParsedSource parsed = GroovyCstParser.parse(source);
        CommonTokenStream tokens = parsed.tokens();

        List<Token> commentTokens =
                tokens.getTokens().stream()
                        .filter(t -> t.getText() != null && t.getText().startsWith("//"))
                        .collect(Collectors.toList());

        assertEquals(1, commentTokens.size());
        assertTrue(commentTokens.get(0).getText().contains("a comment"));
    }

    @Test
    void findsABlockCommentInsideParensOnWhicheverChannelItLandsOn() {
        // Groovy's ignoreTokenInsideParens() logic can push a comment to the hidden channel when
        // it appears inside (...) — the classifier must not assume a fixed channel.
        String source = "def x = foo(/* inline */ 1, 2)\n";
        ParsedSource parsed = GroovyCstParser.parse(source);
        CommonTokenStream tokens = parsed.tokens();

        List<Token> commentTokens =
                tokens.getTokens().stream()
                        .filter(t -> t.getText() != null && t.getText().startsWith("/*"))
                        .collect(Collectors.toList());

        assertEquals(1, commentTokens.size());
        assertTrue(commentTokens.get(0).getText().contains("inline"));
    }

    @Test
    void throwsAClearParseExceptionForInvalidSyntaxInsteadOfSilentlyRecovering() {
        // ANTLR's DefaultErrorStrategy recovers from a syntax error and keeps parsing, producing a
        // best-effort (partially garbage) tree, rather than throwing — found via a corpus survey
        // where malformed/unsupported-syntax input silently produced corrupted output instead of a
        // clear failure. GroovyCstParser must convert any recorded syntax error into an exception.
        String source = "class Foo {\n  def bar( {\n    return 1\n  }\n}\n";
        GroovyParseException e = assertThrows(GroovyParseException.class, () -> GroovyCstParser.parse(source));
        assertTrue(e.getMessage().contains("syntax error"));
    }

    @Test
    void parsesValidSourceWithoutThrowingDespiteTheStricterErrorListener() {
        // Guards against the error listener being too strict and rejecting legitimate Groovy.
        assertEquals(
                "CompilationUnitContext",
                GroovyCstParser.parse("class Foo {\n  def bar() {\n    return 1\n  }\n}\n")
                        .compilationUnit()
                        .getClass()
                        .getSimpleName());
    }
}
