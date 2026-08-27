package dev.groovyfmt.print;

import dev.groovyfmt.doc.Doc;
import dev.groovyfmt.doc.DocRenderer;
import dev.groovyfmt.doc.RenderOptions;
import dev.groovyfmt.parser.GroovyCstParser;
import dev.groovyfmt.parser.ParsedSource;

/** Public entry point: formats a Groovy source file. */
public final class GroovyFormatter {

    private static final RenderOptions OPTIONS = new RenderOptions(100, 4);

    private GroovyFormatter() {}

    public static String format(String source) {
        ParsedSource parsed = GroovyCstParser.parse(source);
        Doc doc = new DocPrintingVisitor().visitCompilationUnit(parsed.compilationUnit());
        String rendered = DocRenderer.render(doc, OPTIONS);
        return rendered.endsWith("\n") ? rendered : rendered + "\n";
    }
}
