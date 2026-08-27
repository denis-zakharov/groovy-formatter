package dev.groovyfmt.cli;

import dev.groovyfmt.print.GroovyFormatter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal CLI: prints the formatted contents of a single file to stdout. The real
 * {@code groovy-format} command (picocli-based, {@code --in-place}/{@code --check} flags over
 * multiple files) lands once the printer covers more of the language — this is just enough to
 * smoke-test the pipeline end-to-end on real files.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: groovy-format <file>");
            System.exit(1);
            return;
        }
        String source = Files.readString(Path.of(args[0]));
        System.out.print(GroovyFormatter.format(source));
    }
}
