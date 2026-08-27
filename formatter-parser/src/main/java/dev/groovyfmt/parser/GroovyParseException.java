package dev.groovyfmt.parser;

/** Thrown when the input cannot be parsed as valid Groovy source. */
public final class GroovyParseException extends RuntimeException {

    public GroovyParseException(String message) {
        super(message);
    }
}
