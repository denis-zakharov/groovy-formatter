package dev.groovyfmt.parser;

import groovyjarjarantlr4.v4.runtime.CharStream;
import groovyjarjarantlr4.v4.runtime.CharStreams;
import groovyjarjarantlr4.v4.runtime.CommonTokenStream;
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
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        // Force full tokenization up front so getTokens() contains every token, including
        // hidden-channel ones, before the parser starts consuming from the stream.
        tokens.fill();

        GroovyLangParser parser = new GroovyLangParser(tokens);
        GroovyParser.CompilationUnitContext cst = parser.compilationUnit();
        return new ParsedSource(cst, tokens);
    }
}
