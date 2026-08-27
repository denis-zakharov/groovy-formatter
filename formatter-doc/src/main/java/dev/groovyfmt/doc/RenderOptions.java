package dev.groovyfmt.doc;

public record RenderOptions(int maxWidth, int indentWidth) {

    public static RenderOptions defaults() {
        return new RenderOptions(100, 2);
    }
}
