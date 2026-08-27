package dev.groovyfmt.cli;

import dev.groovyfmt.parser.GroovyParseException;
import dev.groovyfmt.print.GroovyFormatter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * The {@code groovy-format} command: formats one or more Groovy source files, printing the
 * result to stdout by default, or rewriting files in place / just checking them.
 */
@Command(
        name = "groovy-format",
        mixinStandardHelpOptions = true,
        version = "groovy-format " + GroovyFormatCommand.VERSION,
        description = "Reformats Groovy source files.")
public final class GroovyFormatCommand implements Callable<Integer> {

    // Keep in sync with the `version` set in the root build.gradle.kts.
    static final String VERSION = "0.1.0-SNAPSHOT";

    @Parameters(
            paramLabel = "FILE",
            arity = "1..*",
            description =
                    "Groovy source files, or (with --recursive) directories to format.")
    private List<Path> paths;

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    private Mode mode = new Mode();

    static final class Mode {
        @Option(
                names = {"-i", "--in-place"},
                description = "Write reformatted output back to each file instead of printing it.")
        boolean inPlace;

        @Option(
                names = {"-n", "--check"},
                description =
                        "Print the paths of files that are not already formatted and exit with a "
                                + "non-zero status; nothing is written.")
        boolean check;
    }

    @Option(
            names = {"-r", "--recursive"},
            description =
                    "Recurse into directory arguments, formatting *.groovy, *.gradle, and "
                            + "Jenkinsfile files (skips hidden directories and any directory named "
                            + "'build').")
    private boolean recursive;

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        List<Path> files;
        try {
            files = GroovyFileFinder.find(paths, recursive);
        } catch (IllegalArgumentException e) {
            throw new ParameterException(spec.commandLine(), e.getMessage());
        } catch (IOException e) {
            throw new ParameterException(spec.commandLine(), e.toString());
        }

        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        boolean hadProblem = false;

        for (Path file : files) {
            try {
                String source = Files.readString(file);
                String formatted = GroovyFormatter.format(source);
                if (mode.check) {
                    if (!formatted.equals(source)) {
                        out.println(file);
                        hadProblem = true;
                    }
                } else if (mode.inPlace) {
                    if (!formatted.equals(source)) {
                        Files.writeString(file, formatted);
                    }
                } else {
                    out.print(formatted);
                }
            } catch (IOException | GroovyParseException | UnsupportedOperationException e) {
                err.println("groovy-format: " + file + ": " + e.getMessage());
                hadProblem = true;
            }
        }
        out.flush();
        err.flush();
        return hadProblem ? 1 : 0;
    }
}
