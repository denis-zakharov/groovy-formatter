package dev.groovyfmt.shell.ast;

import java.util.List;

/** A shell "word": a sequence of {@link WordPart}s with no separating whitespace. */
public record Word(List<WordPart> parts) {

    public Word {
        parts = List.copyOf(parts);
    }

    /** True if this word is exactly the unquoted literal text {@code s} — used to recognize reserved words. */
    public boolean isBareLiteral(String s) {
        return parts.size() == 1
                && parts.get(0) instanceof WordPart.Literal l
                && l.text().equals(s);
    }

    /**
     * The word's text with quote markers stripped but expansions rejected — used only to compute
     * a here-doc delimiter's matching text. Throws if the word contains anything other than
     * literal/quoted text (an expansion in a here-doc delimiter is valid shell but out of scope).
     */
    public String plainText() {
        StringBuilder sb = new StringBuilder();
        appendPlain(parts, sb);
        return sb.toString();
    }

    private static void appendPlain(List<WordPart> parts, StringBuilder sb) {
        for (WordPart part : parts) {
            if (part instanceof WordPart.Literal l) {
                sb.append(l.text());
            } else if (part instanceof WordPart.SingleQuoted q) {
                sb.append(q.raw());
            } else if (part instanceof WordPart.DoubleQuoted q) {
                appendPlain(q.parts(), sb);
            } else {
                throw new UnsupportedOperationException(
                        "here-doc delimiter with an expansion is not supported: " + part);
            }
        }
    }
}
