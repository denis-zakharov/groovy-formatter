package dev.groovyfmt.cli;

/**
 * Placeholder CLI entry point. The real {@code groovy-format} command (picocli-based,
 * {@code --in-place}/{@code --check} flags over one or more files) lands once
 * {@code formatter-print} has a working {@code GroovyFormatter.format(String)} — see the phase
 * plan in the project's design notes.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.err.println("groovy-format: not implemented yet");
        System.exit(1);
    }
}
