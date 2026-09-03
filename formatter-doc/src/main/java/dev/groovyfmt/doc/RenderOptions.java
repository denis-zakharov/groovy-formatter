package dev.groovyfmt.doc;

/**
 * @param indentWidth number of columns per indent level. For {@link IndentStyle#TABS}, this is
 *     used only to compute line-fitting width (one tab is treated as {@code indentWidth} columns
 *     wide, a common approximation since actual tab-stop width is a viewer setting) — the emitted
 *     indentation itself is one tab character per level.
 */
public record RenderOptions(int maxWidth, int indentWidth, IndentStyle indentStyle) {

    public enum IndentStyle {
        SPACES,
        TABS
    }

    public RenderOptions(int maxWidth, int indentWidth) {
        this(maxWidth, indentWidth, IndentStyle.SPACES);
    }

    public static RenderOptions defaults() {
        return new RenderOptions(100, 4, IndentStyle.SPACES);
    }

    /** The literal text emitted for one indent level. */
    public String indentUnit() {
        return indentStyle == IndentStyle.TABS ? "\t" : " ".repeat(indentWidth);
    }
}
