package dev.groovyfmt.shellcli;

import picocli.CommandLine;

/** Entry point for the {@code shell-format} command; all logic lives in {@link ShellFormatCommand}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(new CommandLine(new ShellFormatCommand()).execute(args));
    }
}
