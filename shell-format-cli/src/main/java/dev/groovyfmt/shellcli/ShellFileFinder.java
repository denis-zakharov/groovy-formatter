package dev.groovyfmt.shellcli;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the CLI's {@code FILE...} arguments into a concrete, sorted list of files to format.
 * A directory argument requires {@code --recursive} and is walked for {@code *.sh}/{@code *.bash}
 * files, skipping {@code .git}/dot-directories (but never skipping the walk root itself, even if
 * it matches those names). A file argument is always included as-is, regardless of its name.
 */
final class ShellFileFinder {

    private ShellFileFinder() {}

    static List<Path> find(List<Path> inputs, boolean recursive) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                if (!recursive) {
                    throw new IllegalArgumentException(
                            input + " is a directory; pass --recursive to format directories");
                }
                result.addAll(walk(input));
            } else {
                result.add(input);
            }
        }
        return result;
    }

    private static List<Path> walk(Path root) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (!dir.equals(root) && name.startsWith(".")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && isShellSource(file)) {
                            found.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        Collections.sort(found);
        return found;
    }

    private static boolean isShellSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".sh") || name.endsWith(".bash");
    }
}
