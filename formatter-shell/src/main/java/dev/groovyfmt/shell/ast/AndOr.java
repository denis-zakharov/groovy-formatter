package dev.groovyfmt.shell.ast;

import java.util.List;

/** One or more {@link Pipeline}s joined by {@code &&} / {@code ||}. */
public record AndOr(Pipeline first, List<Conjunction> rest) {

    public AndOr {
        rest = List.copyOf(rest);
    }

    /** {@code operator} is {@code "&&"} or {@code "||"}. */
    public record Conjunction(String operator, Pipeline pipeline) {}
}
