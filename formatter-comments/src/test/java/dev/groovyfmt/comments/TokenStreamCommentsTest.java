package dev.groovyfmt.comments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.groovyfmt.parser.GroovyCstParser;
import dev.groovyfmt.parser.ParsedSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenStreamCommentsTest {

    @Test
    void extractsALineComment() {
        List<Comment> comments = extract("class Foo {\n  // hi\n  int x\n}\n");
        assertEquals(1, comments.size());
        assertEquals(Comment.Kind.LINE, comments.get(0).kind());
        assertEquals("// hi", comments.get(0).text());
    }

    @Test
    void extractsAGroovydocCommentDistinctlyFromAPlainBlockComment() {
        List<Comment> comments = extract("class Foo {\n  /**\n   * doc\n   */\n  int x\n}\n");
        assertEquals(1, comments.size());
        assertEquals(Comment.Kind.GROOVYDOC, comments.get(0).kind());
    }

    @Test
    void extractsAPlainBlockComment() {
        // Also exercises Groovy's paren-nesting-dependent channel assignment: this comment sits
        // inside "(...)" and can land on the hidden channel, but must still be found by text.
        List<Comment> comments = extract("def x = foo(/* inline */ 1)\n");
        assertEquals(1, comments.size());
        assertEquals(Comment.Kind.BLOCK, comments.get(0).kind());
        assertEquals("/* inline */", comments.get(0).text());
    }

    @Test
    void doesNotTreatAnEmptyBlockCommentAsGroovydoc() {
        List<Comment> comments = extract("/**/\nclass Foo {}\n");
        assertEquals(1, comments.size());
        assertEquals(Comment.Kind.BLOCK, comments.get(0).kind());
    }

    @Test
    void computesEndLineForAMultiLineBlockComment() {
        List<Comment> comments = extract("/* line1\nline2\nline3 */\nclass Foo {}\n");
        assertEquals(1, comments.size());
        Comment c = comments.get(0);
        assertEquals(1, c.line());
        assertEquals(3, c.endLine());
    }

    @Test
    void aSingleLineCommentHasNoExtraEndLine() {
        List<Comment> comments = extract("// hi\nclass Foo {}\n");
        assertEquals(1, comments.get(0).line());
        assertEquals(1, comments.get(0).endLine());
    }

    private static List<Comment> extract(String source) {
        ParsedSource parsed = GroovyCstParser.parse(source);
        return TokenStreamComments.extract(parsed.tokens());
    }
}
