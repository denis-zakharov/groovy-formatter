package dev.groovyfmt.comments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.groovyfmt.parser.GroovyCstParser;
import dev.groovyfmt.parser.ParsedSource;
import java.util.List;
import org.apache.groovy.parser.antlr4.GroovyParser;
import org.junit.jupiter.api.Test;

class CommentAttacherTest {

    @Test
    void attachesALeadingCommentAsItsOwnItemBeforeTheFollowingMember() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n  // leading\n  int x\n}\n");

        assertEquals(2, items.size());
        assertTrue(items.get(0).isComment());
        assertEquals("// leading", items.get(0).standaloneComment().text());
        assertFalse(items.get(0).blankBefore());
        assertTrue(items.get(1).isNode());
        assertNull(items.get(1).trailingComment());
    }

    @Test
    void attachesATrailingSameLineCommentToItsMemberInsteadOfANewItem() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n  int x // trailing\n}\n");

        assertEquals(1, items.size());
        assertTrue(items.get(0).isNode());
        assertEquals("// trailing", items.get(0).trailingComment().text());
    }

    @Test
    void preservesASingleBlankLineBetweenMembers() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n  int x\n\n  int y\n}\n");

        assertEquals(2, items.size());
        assertFalse(items.get(0).blankBefore());
        assertTrue(items.get(1).blankBefore());
    }

    @Test
    void capsMultipleConsecutiveBlankLinesAtOneWithoutErroring() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n  int x\n\n\n\n  int y\n}\n");

        assertEquals(2, items.size());
        assertTrue(items.get(1).blankBefore());
    }

    @Test
    void doesNotInventABlankLineWhenSourceHasNone() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n  int x\n  int y\n}\n");

        assertEquals(2, items.size());
        assertFalse(items.get(1).blankBefore());
    }

    @Test
    void neverPutsABlankLineBeforeTheFirstMemberEvenIfSourceHasOneAfterTheBrace() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n\n  int x\n}\n");

        assertEquals(1, items.size());
        assertFalse(items.get(0).blankBefore());
    }

    @Test
    void handlesADanglingCommentAfterTheLastMember() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n  int x\n  // trailing note\n}\n");

        assertEquals(2, items.size());
        assertTrue(items.get(1).isComment());
        assertEquals("// trailing note", items.get(1).standaloneComment().text());
    }

    @Test
    void handlesAContainerWithOnlyACommentAndNoMembers() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n  // just a note\n}\n");

        assertEquals(1, items.size());
        assertTrue(items.get(0).isComment());
    }

    @Test
    void multipleLeadingCommentsBeforeOneMemberEachBecomeTheirOwnItem() {
        List<CommentAttacher.Item> items = attachClassMembers("class Foo {\n  // one\n  // two\n  int x\n}\n");

        assertEquals(3, items.size());
        assertEquals("// one", items.get(0).standaloneComment().text());
        assertEquals("// two", items.get(1).standaloneComment().text());
        assertFalse(items.get(1).blankBefore());
        assertTrue(items.get(2).isNode());
    }

    @Test
    void excludesACommentNestedInsideAMemberFromThisLevelsItems() {
        // attach() alone doesn't throw here: it can't know whether some deeper attach() call
        // (there isn't one, in this case — nothing prints an argument list's own siblings) will
        // claim it. It just leaves it unclaimed.
        String source = "class Foo {\n  int x = foo(/* nested */ 1)\n  int y\n}\n";
        ParsedSource parsed = GroovyCstParser.parse(source);
        List<Comment> comments = TokenStreamComments.extract(parsed.tokens());
        CommentAttacher attacher = new CommentAttacher(comments);

        List<CommentAttacher.Item> items = attach(attacher, parsed);

        assertEquals(2, items.size());
        assertTrue(items.stream().noneMatch(CommentAttacher.Item::isComment));
        assertThrows(UnsupportedOperationException.class, attacher::assertAllCommentsClaimed);
    }

    @Test
    void assertAllCommentsClaimedPassesWhenEveryCommentWasAttached() {
        String source = "class Foo {\n  // leading\n  int x\n}\n";
        ParsedSource parsed = GroovyCstParser.parse(source);
        List<Comment> comments = TokenStreamComments.extract(parsed.tokens());
        CommentAttacher attacher = new CommentAttacher(comments);

        attach(attacher, parsed);

        attacher.assertAllCommentsClaimed(); // must not throw
    }

    private List<CommentAttacher.Item> attachClassMembers(String source) {
        ParsedSource parsed = GroovyCstParser.parse(source);
        List<Comment> comments = TokenStreamComments.extract(parsed.tokens());
        CommentAttacher attacher = new CommentAttacher(comments);
        return attach(attacher, parsed);
    }

    private List<CommentAttacher.Item> attach(CommentAttacher attacher, ParsedSource parsed) {
        GroovyParser.ClassBodyContext body =
                parsed.compilationUnit()
                        .scriptStatements()
                        .scriptStatement(0)
                        .typeDeclaration()
                        .classDeclaration()
                        .classBody();
        return attacher.attach(
                body.classBodyDeclaration(),
                body.LBRACE().getSymbol().getTokenIndex(),
                body.RBRACE().getSymbol().getTokenIndex());
    }
}
