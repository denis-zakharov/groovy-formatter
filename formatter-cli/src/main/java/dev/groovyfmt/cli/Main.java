package dev.groovyfmt.cli;

import picocli.CommandLine;

/** Entry point for the {@code groovy-format} command; all logic lives in {@link GroovyFormatCommand}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // picocli probes for groovy.lang.Closure on the classpath to support closure-based
        // factories; merely loading that class triggers Groovy's full metaclass/DGM runtime
        // init (unused here — this CLI only touches Groovy's ANTLR4 parser classes), which
        // isn't resource-complete under GraalVM native-image and crashes at startup. Disabled
        // since we never register a Groovy closure as a picocli factory.
        System.setProperty("picocli.disable.closures", "true");
        System.exit(new CommandLine(new GroovyFormatCommand()).execute(args));
    }
}
