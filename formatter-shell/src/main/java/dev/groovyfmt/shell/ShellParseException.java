package dev.groovyfmt.shell;

/** Thrown when the input cannot be parsed as valid shell source. */
public final class ShellParseException extends RuntimeException {

    private final int line;
    private final int column;

    public ShellParseException(String message, int line, int column) {
        super(message + " at line " + line + ", column " + column);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
