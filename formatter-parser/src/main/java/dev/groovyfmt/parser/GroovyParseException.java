package dev.groovyfmt.parser;

import java.util.List;

/** Thrown when the input cannot be parsed as valid Groovy source. */
public final class GroovyParseException extends RuntimeException {

    private final List<SyntaxError> errors;

    public GroovyParseException(String message, List<SyntaxError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    /** The individual syntax errors ANTLR recorded before parsing gave up, in source order. */
    public List<SyntaxError> errors() {
        return errors;
    }

    /** One recorded syntax error: a 1-based source line, a 0-based column, and ANTLR's message. */
    public record SyntaxError(int line, int column, String message) {}
}
