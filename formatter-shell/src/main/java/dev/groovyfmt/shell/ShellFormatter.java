package dev.groovyfmt.shell;

import dev.groovyfmt.doc.Doc;
import dev.groovyfmt.doc.DocRenderer;
import dev.groovyfmt.doc.RenderOptions;
import dev.groovyfmt.shell.ast.Script;
import dev.groovyfmt.shell.parser.Parser;
import dev.groovyfmt.shell.print.ShellPrinter;

/** Public entry point: formats a shell (sh/bash) source file. */
public final class ShellFormatter {

    private ShellFormatter() {}

    public static String format(String source) {
        return format(source, RenderOptions.defaults());
    }

    public static String format(String source, RenderOptions options) {
        Script script = Parser.parse(source);
        Doc doc = new ShellPrinter().print(script);
        String rendered = DocRenderer.render(doc, options);
        return rendered.endsWith("\n") || rendered.isEmpty() ? rendered : rendered + "\n";
    }
}
