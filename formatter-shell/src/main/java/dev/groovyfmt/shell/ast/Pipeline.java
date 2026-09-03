package dev.groovyfmt.shell.ast;

import java.util.List;

/** One or more {@link Command}s joined by {@code |} (or {@code |&}), optionally {@code !}-negated. */
public record Pipeline(boolean negated, List<Command> commands, List<Boolean> pipeStderr) {

    public Pipeline {
        commands = List.copyOf(commands);
        pipeStderr = List.copyOf(pipeStderr);
    }
}
