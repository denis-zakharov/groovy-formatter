package dev.groovyfmt.print;

import dev.groovyfmt.comments.Comment;
import dev.groovyfmt.comments.CommentAttacher;
import dev.groovyfmt.comments.TokenStreamComments;
import dev.groovyfmt.doc.Doc;
import dev.groovyfmt.doc.DocRenderer;
import dev.groovyfmt.doc.RenderOptions;
import dev.groovyfmt.parser.GroovyCstParser;
import dev.groovyfmt.parser.ParsedSource;
import java.util.List;

/** Public entry point: formats a Groovy source file. */
public final class GroovyFormatter {

    private static final RenderOptions OPTIONS = new RenderOptions(100, 4);

    private GroovyFormatter() {}

    public static String format(String source) {
        // A shebang line (#!/usr/bin/env groovy) is lexed with `-> skip` — it never becomes a
        // token, so it's invisible to the CST and would otherwise be silently dropped, since the
        // printer builds output purely from visited nodes. The parser itself handles a shebang
        // fine without any stripping (confirmed against the real grammar/jar), so the source is
        // parsed as-is; the shebang text is just separately peeled off and reattached verbatim.
        String shebang = extractShebangLine(source);

        ParsedSource parsed = GroovyCstParser.parse(source);
        List<Comment> comments = TokenStreamComments.extract(parsed.tokens());
        CommentAttacher commentAttacher = new CommentAttacher(comments);
        Doc doc = new DocPrintingVisitor(commentAttacher, source).visitCompilationUnit(parsed.compilationUnit());
        commentAttacher.assertAllCommentsClaimed();
        String rendered = DocRenderer.render(doc, OPTIONS);
        String result = rendered.endsWith("\n") ? rendered : rendered + "\n";
        return shebang == null ? result : shebang + "\n" + result;
    }

    private static String extractShebangLine(String source) {
        if (!source.startsWith("#!")) {
            return null;
        }
        int newlineIndex = source.indexOf('\n');
        return newlineIndex == -1 ? source : source.substring(0, newlineIndex);
    }
}
