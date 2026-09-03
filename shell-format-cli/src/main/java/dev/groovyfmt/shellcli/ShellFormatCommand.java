package dev.groovyfmt.shellcli;

import dev.groovyfmt.doc.RenderOptions;
import dev.groovyfmt.shell.ShellFormatter;
import dev.groovyfmt.shell.ShellParseException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * The {@code shell-format} command: formats one or more shell (sh/bash) source files, printing
 * the result to stdout by default, or rewriting files in place / just checking them.
 */
@Command(
        name = "shell-format",
        mixinStandardHelpOptions = true,
        version = "shell-format " + ShellFormatCommand.VERSION,
        description = "Reformats shell (sh/bash) source files.")
public final class ShellFormatCommand implements Callable<Integer> {

    // Keep in sync with the `version` set in the root build.gradle.kts.
    static final String VERSION = "0.1.0-SNAPSHOT";

    @Parameters(
            paramLabel = "FILE",
            arity = "1..*",
            description =
                    "Shell source files, or (with --recursive) directories to format. Pass '-' "
                            + "alone to read a single source from stdin and write the formatted "
                            + "result to stdout.")
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
                    "Recurse into directory arguments, formatting *.sh and *.bash files (skips "
                            + "hidden directories).")
    private boolean recursive;

    @Option(
            names = {"-w", "--line-length"},
            paramLabel = "N",
            description = "Maximum line length before wrapping. Default: ${DEFAULT-VALUE}.")
    private int lineLength = RenderOptions.defaults().maxWidth();

    @Option(
            names = {"-x", "--indent-size"},
            paramLabel = "N",
            description = "Columns per indent level. Default: ${DEFAULT-VALUE}.")
    private int indentSize = RenderOptions.defaults().indentWidth();

    @Option(
            names = "--use-tabs",
            description = "Indent with tabs instead of spaces.")
    private boolean useTabs;

    @Spec private CommandSpec spec;

    private RenderOptions renderOptions() {
        return new RenderOptions(
                lineLength, indentSize, useTabs ? RenderOptions.IndentStyle.TABS : RenderOptions.IndentStyle.SPACES);
    }

    @Override
    public Integer call() {
        boolean stdin = paths.size() == 1 && isStdinMarker(paths.get(0));
        if (!stdin && paths.stream().anyMatch(ShellFormatCommand::isStdinMarker)) {
            throw new ParameterException(
                    spec.commandLine(), "'-' (stdin) cannot be combined with other files");
        }
        if (stdin) {
            if (mode.inPlace) {
                throw new ParameterException(
                        spec.commandLine(), "--in-place cannot be used with stdin ('-')");
            }
            return callStdin();
        }

        List<Path> files;
        try {
            files = ShellFileFinder.find(paths, recursive);
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
                String formatted = ShellFormatter.format(source, renderOptions());
                if (mode.check) {
                    if (!formatted.equals(source)) {
                        out.println(file);
                        hadProblem = true;
                    }
                } else if (mode.inPlace) {
                    if (!formatted.equals(source)) {
                        writeInPlace(file, formatted);
                    }
                } else {
                    out.print(formatted);
                }
            } catch (ShellParseException e) {
                err.println(file + ":" + e.line() + ":" + e.column() + ": error: " + e.getMessage());
                hadProblem = true;
            } catch (IOException | UnsupportedOperationException e) {
                err.println("shell-format: " + file + ": " + e.getMessage());
                hadProblem = true;
            }
        }
        out.flush();
        err.flush();
        return hadProblem ? 1 : 0;
    }

    private static boolean isStdinMarker(Path path) {
        return path.toString().equals("-");
    }

    private Integer callStdin() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            String formatted = ShellFormatter.format(source, renderOptions());
            int exitCode = 0;
            if (mode.check) {
                if (!formatted.equals(source)) {
                    out.println("<stdin>");
                    exitCode = 1;
                }
            } else {
                out.print(formatted);
            }
            out.flush();
            return exitCode;
        } catch (ShellParseException e) {
            err.println("<stdin>:" + e.line() + ":" + e.column() + ": error: " + e.getMessage());
            err.flush();
            return 1;
        } catch (IOException | UnsupportedOperationException e) {
            err.println("shell-format: <stdin>: " + e.getMessage());
            err.flush();
            return 1;
        }
    }

    /**
     * Writes {@code formatted} to {@code file} all-or-nothing: the new content is written to a
     * sibling temp file first and only swapped into place with an atomic rename once it's fully
     * on disk, so a write failure (or a crash) partway through can never leave {@code file} with
     * a mix of old and new content.
     */
    private static void writeInPlace(Path file, String formatted) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, formatted, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows) — nothing to preserve.
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
