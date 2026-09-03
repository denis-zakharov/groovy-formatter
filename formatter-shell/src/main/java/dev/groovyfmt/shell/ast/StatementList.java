package dev.groovyfmt.shell.ast;

import java.util.List;

/**
 * A sequence of {@link Statement}s with comments and blank-line placement preserved, mirroring
 * how {@code formatter-print} preserves blank lines/comments around Groovy statements: blank
 * lines are kept but capped at one, comments are never invented or dropped.
 */
public record StatementList(
        List<Entry> entries, List<Comment> danglingComments, boolean blankLineBeforeDangling) {

    public StatementList {
        entries = List.copyOf(entries);
        danglingComments = List.copyOf(danglingComments);
    }

    public boolean isEmpty() {
        return entries.isEmpty() && danglingComments.isEmpty();
    }

    /**
     * @param trailingComment same-line comment after the statement's own text, or {@code null}
     */
    public record Entry(
            List<Comment> leadingComments,
            boolean blankLineBefore,
            Statement statement,
            Comment trailingComment) {}
}
