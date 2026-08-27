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
        ParsedSource parsed = GroovyCstParser.parse(source);
        List<Comment> comments = TokenStreamComments.extract(parsed.tokens());
        CommentAttacher commentAttacher = new CommentAttacher(comments);
        Doc doc = new DocPrintingVisitor(commentAttacher).visitCompilationUnit(parsed.compilationUnit());
        commentAttacher.assertAllCommentsClaimed();
        String rendered = DocRenderer.render(doc, OPTIONS);
        return rendered.endsWith("\n") ? rendered : rendered + "\n";
    }
}
