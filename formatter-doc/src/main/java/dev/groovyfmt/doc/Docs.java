package dev.groovyfmt.doc;

import java.util.ArrayList;
import java.util.List;

/** Static factory/combinator methods for building {@link Doc} trees. */
public final class Docs {

    public static final Doc NIL = new Doc.Text("");
    public static final Doc LINE = new Doc.Line();
    public static final Doc SOFTLINE = new Doc.SoftLine();
    public static final Doc HARDLINE = new Doc.HardLine();
    public static final Doc BREAK_PARENT = new Doc.BreakParent();

    private Docs() {}

    public static Doc text(String value) {
        return new Doc.Text(value);
    }

    public static Doc concat(Doc... parts) {
        return new Doc.Concat(List.of(parts));
    }

    public static Doc concat(List<Doc> parts) {
        return new Doc.Concat(List.copyOf(parts));
    }

    public static Doc group(Doc child) {
        return new Doc.Group(child);
    }

    public static Doc indent(Doc child) {
        return new Doc.Indent(child);
    }

    public static Doc ifBreak(Doc whenBroken, Doc whenFlat) {
        return new Doc.IfBreak(whenBroken, whenFlat);
    }

    public static Doc lineSuffix(Doc child) {
        return new Doc.LineSuffix(child);
    }

    public static Doc indentedVerbatim(String raw, String baseIndent) {
        return new Doc.IndentedVerbatim(raw, baseIndent);
    }

    /** Joins {@code docs} with {@code separator} placed between each pair. */
    public static Doc join(Doc separator, List<Doc> docs) {
        List<Doc> parts = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
                parts.add(separator);
            }
            parts.add(docs.get(i));
        }
        return new Doc.Concat(List.copyOf(parts));
    }
}
