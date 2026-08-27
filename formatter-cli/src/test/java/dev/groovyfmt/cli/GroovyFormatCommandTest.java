package dev.groovyfmt.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.groovyfmt.print.GroovyFormatter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class GroovyFormatCommandTest {

    private static final String FORMATTED = "class Foo {}\n";
    private static final String UNFORMATTED = "class Foo{\nint x=1\n}\n";
    private static final String UNPARSEABLE = "class Foo {\n";

    @TempDir Path tempDir;

    private record Result(int exitCode, String out, String err) {}

    private Result run(String... args) {
        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();
        CommandLine cmd = new CommandLine(new GroovyFormatCommand());
        cmd.setOut(new PrintWriter(outSw));
        cmd.setErr(new PrintWriter(errSw));
        int exitCode = cmd.execute(args);
        return new Result(exitCode, outSw.toString(), errSw.toString());
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private Result runWithStdin(String stdin, String... args) {
        InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        try {
            return run(args);
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void singleFileNoFlagsPrintsFormattedOutput() throws IOException {
        Path file = writeFile("Foo.groovy", UNFORMATTED);

        Result result = run(file.toString());

        assertEquals(0, result.exitCode());
        assertEquals(GroovyFormatter.format(UNFORMATTED), result.out());
        assertEquals(UNFORMATTED, Files.readString(file));
    }

    @Test
    void twoFilesNoFlagsConcatenatesOutput() throws IOException {
        Path a = writeFile("A.groovy", FORMATTED);
        Path b = writeFile("B.groovy", UNFORMATTED);

        Result result = run(a.toString(), b.toString());

        assertEquals(0, result.exitCode());
        assertEquals(GroovyFormatter.format(FORMATTED) + GroovyFormatter.format(UNFORMATTED), result.out());
    }

    @Test
    void inPlaceRewritesUnformattedFile() throws IOException {
        Path file = writeFile("Foo.groovy", UNFORMATTED);

        Result result = run("-i", file.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.out());
        assertEquals(GroovyFormatter.format(UNFORMATTED), Files.readString(file));
    }

    @Test
    void inPlaceLeavesAlreadyFormattedFileUnchanged() throws IOException {
        Path file = writeFile("Foo.groovy", FORMATTED);

        Result result = run("--in-place", file.toString());

        assertEquals(0, result.exitCode());
        assertEquals(FORMATTED, Files.readString(file));
    }

    @Test
    void checkReportsUnformattedFile() throws IOException {
        Path file = writeFile("Foo.groovy", UNFORMATTED);

        Result result = run("--check", file.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains(file.toString()));
        assertEquals(UNFORMATTED, Files.readString(file));
    }

    @Test
    void checkIsSilentOnAlreadyFormattedFile() throws IOException {
        Path file = writeFile("Foo.groovy", FORMATTED);

        Result result = run("-n", file.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.out());
    }

    @Test
    void inPlaceAndCheckTogetherIsUsageError() throws IOException {
        Path file = writeFile("Foo.groovy", UNFORMATTED);

        Result result = run("-i", "-n", file.toString());

        assertEquals(2, result.exitCode());
        assertFalse(result.err().isEmpty());
        assertEquals(UNFORMATTED, Files.readString(file));
    }

    @Test
    void directoryWithoutRecursiveIsUsageError() throws IOException {
        Files.createDirectory(tempDir.resolve("src"));

        Result result = run(tempDir.resolve("src").toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--recursive"));
    }

    @Test
    void recursiveWalksMatchingFilesOnly() throws IOException {
        Path src = tempDir.resolve("src");
        writeFile("src/A.groovy", FORMATTED);
        writeFile("src/b.gradle", FORMATTED);
        writeFile("src/Jenkinsfile", FORMATTED);
        writeFile("src/notes.txt", "not groovy");
        writeFile("src/.git/hook.groovy", FORMATTED);
        writeFile("src/build/Out.groovy", FORMATTED);

        Result result = run("-r", "--check", src.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.out());
    }

    @Test
    void recursiveExcludesGradleKtsFiles() throws IOException {
        Path src = tempDir.resolve("src");
        writeFile("src/settings.gradle.kts", "rootProject.name = \"x\"");

        Result result = run("-r", src.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.out());
    }

    @Test
    void oneBadFileDoesNotAbortTheBatch() throws IOException {
        Path good = writeFile("good.groovy", UNFORMATTED);
        Path bad = writeFile("bad.groovy", UNPARSEABLE);

        Result result = run(good.toString(), bad.toString());

        assertEquals(1, result.exitCode());
        assertEquals(GroovyFormatter.format(UNFORMATTED), result.out());
        assertTrue(result.err().contains(bad.toString()));
    }

    @Test
    void helpMentionsAllFlags() {
        Result result = run("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("-i"));
        assertTrue(result.out().contains("--in-place"));
        assertTrue(result.out().contains("-n"));
        assertTrue(result.out().contains("--check"));
        assertTrue(result.out().contains("-r"));
        assertTrue(result.out().contains("--recursive"));
    }

    @Test
    void versionPrintsCommandNameAndVersion() {
        Result result = run("--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("groovy-format"));
        assertTrue(result.out().contains(GroovyFormatCommand.VERSION));
    }

    @Test
    void stdinNoFlagsPrintsFormattedOutputToStdout() {
        Result result = runWithStdin(UNFORMATTED, "-");

        assertEquals(0, result.exitCode());
        assertEquals(GroovyFormatter.format(UNFORMATTED), result.out());
    }

    @Test
    void stdinCheckReportsUnformattedInput() {
        Result result = runWithStdin(UNFORMATTED, "--check", "-");

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("<stdin>"));
    }

    @Test
    void stdinCheckIsSilentOnAlreadyFormattedInput() {
        Result result = runWithStdin(FORMATTED, "--check", "-");

        assertEquals(0, result.exitCode());
        assertEquals("", result.out());
    }

    @Test
    void stdinReportsParseErrorOnStderr() {
        Result result = runWithStdin(UNPARSEABLE, "-");

        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("<stdin>"));
    }

    @Test
    void stdinWithInPlaceIsUsageError() {
        Result result = runWithStdin(UNFORMATTED, "-i", "-");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--in-place"));
    }

    @Test
    void stdinCombinedWithOtherFileIsUsageError() throws IOException {
        Path file = writeFile("Foo.groovy", FORMATTED);

        Result result = runWithStdin(UNFORMATTED, "-", file.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("stdin"));
    }

    @Test
    void noArgumentsIsUsageError() {
        Result result = run();

        assertEquals(2, result.exitCode());
    }
}
