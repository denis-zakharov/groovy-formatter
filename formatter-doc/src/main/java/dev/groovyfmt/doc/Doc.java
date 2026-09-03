package dev.groovyfmt.doc;

import java.util.List;

/**
 * Wadler/Prettier-style pretty-printing IR. A {@link Doc} tree is rendered to text by
 * {@link DocRenderer}, which decides — per {@link Group} — whether that group's content
 * fits on the current line ("flat") or must be broken across multiple lines.
 */
public sealed interface Doc
        permits Doc.Text, Doc.Concat, Doc.Line, Doc.SoftLine, Doc.HardLine,
                Doc.Group, Doc.Indent, Doc.IfBreak, Doc.LineSuffix, Doc.BreakParent,
                Doc.IndentedVerbatim {

    /** Literal text containing no line breaks. */
    record Text(String value) implements Doc {}

    /** A sequence of docs rendered one after another. */
    record Concat(List<Doc> parts) implements Doc {}

    /** A space when the enclosing group is flat, a newline when it is broken. */
    record Line() implements Doc {}

    /** Nothing when the enclosing group is flat, a newline when it is broken. */
    record SoftLine() implements Doc {}

    /** Always a newline; forces every enclosing group to break. */
    record HardLine() implements Doc {}

    /** Tries to render {@code child} flat on one line if it fits; otherwise breaks it. */
    record Group(Doc child) implements Doc {}

    /** Increases the indentation applied after newlines produced inside {@code child}. */
    record Indent(Doc child) implements Doc {}

    /** Chooses {@code whenBroken} if the enclosing group breaks, {@code whenFlat} otherwise. */
    record IfBreak(Doc whenBroken, Doc whenFlat) implements Doc {}

    /**
     * Defers {@code child} to just before the next newline, without counting against the
     * current group's fits calculation. Used for trailing same-line comments.
     */
    record LineSuffix(Doc child) implements Doc {}

    /** A marker with no text of its own that forces every enclosing group to break. */
    record BreakParent() implements Doc {}

    /**
     * Multi-line literal text (a triple-quoted string body) printed verbatim except for its own
     * indentation: {@code raw}'s first line is emitted as-is, but every subsequent line has
     * {@code baseIndent} — the leading whitespace of the source line the literal started on —
     * stripped and replaced with the indentation the literal is rendered at, so the string's
     * *relative* indentation survives being moved to a different nesting depth. Always forces
     * every enclosing group to break, since it contains real newlines no matter the mode.
     */
    record IndentedVerbatim(String raw, String baseIndent) implements Doc {}
}
