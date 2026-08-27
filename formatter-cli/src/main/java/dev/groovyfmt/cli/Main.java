package dev.groovyfmt.cli;

import picocli.CommandLine;

/** Entry point for the {@code groovy-format} command; all logic lives in {@link GroovyFormatCommand}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(new CommandLine(new GroovyFormatCommand()).execute(args));
    }
}
