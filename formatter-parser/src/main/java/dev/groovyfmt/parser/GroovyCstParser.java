package dev.groovyfmt.parser;

import groovyjarjarantlr4.v4.runtime.ANTLRErrorListener;
import groovyjarjarantlr4.v4.runtime.CharStream;
import groovyjarjarantlr4.v4.runtime.CharStreams;
import groovyjarjarantlr4.v4.runtime.CommonTokenStream;
import groovyjarjarantlr4.v4.runtime.RecognitionException;
import groovyjarjarantlr4.v4.runtime.Recognizer;
import java.util.ArrayList;
import java.util.List;
import org.apache.groovy.parser.antlr4.GroovyLangLexer;
import org.apache.groovy.parser.antlr4.GroovyLangParser;
import org.apache.groovy.parser.antlr4.GroovyParser;

/**
 * Wraps construction of Groovy's own "Parrot" ANTLR4 lexer/parser (as shipped inside the
 * {@code org.apache.groovy:groovy} jar) so the rest of the codebase never has to touch
 * {@code groovyjarjarantlr4.v4.runtime.*} or {@code org.apache.groovy.parser.antlr4.*} directly.
 *
 * <p>Groovy relocates its own copy of the ANTLR4 runtime under {@code groovyjarjarantlr4}; there
 * is no real {@code org.antlr} package in the published jar at all. This module must never gain a
 * dependency on the real {@code org.antlr:antlr4-runtime} artifact, or these types stop lining up.
 */
public final class GroovyCstParser {

    private GroovyCstParser() {}

    public static ParsedSource parse(String source) {
        CharStream charStream = CharStreams.fromString(source);
        GroovyLangLexer lexer = new GroovyLangLexer(charStream);
        ThrowingErrorListener lexerErrors = new ThrowingErrorListener();
        lexer.removeErrorListeners();
        lexer.addErrorListener(lexerErrors);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        // Force full tokenization up front so getTokens() contains every token, including
        // hidden-channel ones, before the parser starts consuming from the stream.
        tokens.fill();
        lexerErrors.throwIfAny();

        GroovyLangParser parser = new GroovyLangParser(tokens);
        ThrowingErrorListener parserErrors = new ThrowingErrorListener();
        parser.removeErrorListeners();
        parser.addErrorListener(parserErrors);

        GroovyParser.CompilationUnitContext cst = parser.compilationUnit();
        // ANTLR's DefaultErrorStrategy recovers from a syntax error and keeps parsing (producing a
        // best-effort, partially-garbage tree) rather than throwing — left unchecked, that garbage
        // tree would silently flow into the printer and produce corrupted output instead of a
        // clear failure. Surface any recorded errors now that parsing has finished.
        parserErrors.throwIfAny();

        return new ParsedSource(cst, tokens);
    }

    // A single listener class services both the lexer (whose Recognizer is generic over Integer
    // token-type symbols) and the parser (generic over Token symbols) by implementing
    // ANTLRErrorListener<Object>, a supertype of both.
    private static final class ThrowingErrorListener implements ANTLRErrorListener<Object> {
        private final List<GroovyParseException.SyntaxError> errors = new ArrayList<>();

        @Override
        public <T> void syntaxError(
                Recognizer<T, ?> recognizer,
                T offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e) {
            errors.add(new GroovyParseException.SyntaxError(line, charPositionInLine, msg));
        }

        void throwIfAny() {
            if (errors.isEmpty()) {
                return;
            }
            StringBuilder message =
                    new StringBuilder("input is not valid Groovy source (")
                            .append(errors.size())
                            .append(" syntax error(s)):");
            for (GroovyParseException.SyntaxError error : errors) {
                message.append("\nline ").append(error.line()).append(':').append(error.column())
                        .append(' ').append(error.message());
            }
            throw new GroovyParseException(message.toString(), errors);
        }
    }
}
