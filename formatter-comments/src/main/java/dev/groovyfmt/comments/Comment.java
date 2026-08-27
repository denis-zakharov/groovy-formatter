package dev.groovyfmt.comments;

/**
 * A single {@code //} or {@code /* *}{@code /} comment found in the token stream, keyed by its
 * token index so callers can reason about where it falls relative to CST nodes.
 *
 * @param text the comment's raw source text, verbatim (e.g. {@code "// hi"} or {@code "/** doc
 *     *}{@code /"}) — never reformatted
 * @param kind LINE ({@code //}), BLOCK ({@code /* *}{@code /}), or GROOVYDOC ({@code /** *}{@code
 *     /})
 * @param line the source line the comment's first character is on (1-based, matching ANTLR)
 * @param tokenIndex the comment token's index in the full token stream
 */
public record Comment(String text, Kind kind, int line, int tokenIndex) {

    public enum Kind {
        LINE,
        BLOCK,
        GROOVYDOC
    }

    /** The last source line this comment spans — equal to {@link #line} unless it's multi-line. */
    public int endLine() {
        int extraLines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                extraLines++;
            }
        }
        return line + extraLines;
    }
}
