package dev.groovyfmt.comments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.groovy.parser.antlr4.GroovyParser;

/**
 * Interleaves a list of sibling CST nodes (class members, block statements, top-level
 * declarations, ...) with the comments that fall between/around them, and decides where a blank
 * line should be preserved.
 *
 * <p>Pure data: never builds a {@code Doc} and never calls into the printer, so it can be tested
 * directly against a parsed token stream without depending on {@code formatter-print}.
 *
 * <p>Blank-line detection needs no separate line-by-line scan of the source: between two
 * consecutive siblings in the same list, the only things that can occupy a line are whitespace, a
 * real newline, or a comment (already extracted separately) — real code there would itself be
 * another sibling. So "is there a blank line between two items" reduces to a plain source-line
 * gap check once each item's true end line is known (comments spanning multiple lines use {@link
 * Comment#endLine()}, not their start line).
 *
 * <p>One instance is shared across an entire file's print: the printer calls {@link #attach} once
 * per container it walks (the compilation unit, each class body, each block). A comment nested
 * inside a single sibling's own token span (e.g. inside an argument list) is left for a deeper
 * call to claim — {@code attach} does not know whether one exists. After the whole tree has been
 * visited, {@link #assertAllCommentsClaimed()} catches any comment nobody ever claimed, rather
 * than silently dropping it.
 */
public final class CommentAttacher {

    private final List<Comment> comments;
    private final Set<Integer> claimedTokenIndices = new HashSet<>();

    public CommentAttacher(List<Comment> comments) {
        this.comments = comments;
    }

    /**
     * @param siblings the sibling nodes, in source order
     * @param containerStartTokenIndex token index strictly before which comments are not
     *     considered part of this container (e.g. the container's opening brace's token index, or
     *     -1 for a container with no opening delimiter, like a whole compilation unit)
     * @param containerStopTokenIndex token index strictly after which comments are not considered
     *     part of this container (e.g. the closing brace's token index, or EOF's)
     */
    public List<Item> attach(
            List<? extends GroovyParser.GroovyParserRuleContext> siblings,
            int containerStartTokenIndex,
            int containerStopTokenIndex) {
        List<Comment> scoped = new ArrayList<>();
        for (Comment c : comments) {
            if (c.tokenIndex() <= containerStartTokenIndex || c.tokenIndex() >= containerStopTokenIndex) {
                continue;
            }
            if (isNestedInsideASibling(c, siblings)) {
                // Not this level's concern — a deeper attach() call (e.g. this sibling's own
                // block/class body) may claim it. If nothing ever does, assertAllCommentsClaimed()
                // catches it.
                continue;
            }
            scoped.add(c);
        }

        List<Item> items = new ArrayList<>();
        int commentIdx = 0;
        int prevEndLine = -1;

        for (GroovyParser.GroovyParserRuleContext node : siblings) {
            int nodeStartTokenIndex = node.getStart().getTokenIndex();
            while (commentIdx < scoped.size() && scoped.get(commentIdx).tokenIndex() < nodeStartTokenIndex) {
                prevEndLine = emit(items, scoped.get(commentIdx), prevEndLine);
                commentIdx++;
            }
            boolean blank = prevEndLine >= 0 && (node.getStart().getLine() - prevEndLine) >= 2;
            items.add(new Item(node, null, null, !items.isEmpty() && blank));
            prevEndLine = node.getStop().getLine();
        }

        while (commentIdx < scoped.size()) {
            prevEndLine = emit(items, scoped.get(commentIdx), prevEndLine);
            commentIdx++;
        }

        return items;
    }

    private static boolean isNestedInsideASibling(
            Comment c, List<? extends GroovyParser.GroovyParserRuleContext> siblings) {
        for (GroovyParser.GroovyParserRuleContext node : siblings) {
            int start = node.getStart().getTokenIndex();
            int stop = node.getStop().getTokenIndex();
            if (c.tokenIndex() > start && c.tokenIndex() < stop) {
                return true;
            }
        }
        return false;
    }

    /** Appends {@code c} as a new standalone item, or attaches it as the previous node's trailing comment. */
    private int emit(List<Item> items, Comment c, int prevEndLine) {
        claimedTokenIndices.add(c.tokenIndex());
        boolean isTrailingOnPreviousLine = prevEndLine >= 0 && c.line() == prevEndLine && !items.isEmpty();
        if (isTrailingOnPreviousLine && items.get(items.size() - 1).isNode()) {
            Item last = items.get(items.size() - 1);
            items.set(items.size() - 1, new Item(last.node(), null, c, last.blankBefore()));
        } else {
            boolean blank = prevEndLine >= 0 && (c.line() - prevEndLine) >= 2;
            items.add(new Item(null, c, null, !items.isEmpty() && blank));
        }
        return c.endLine();
    }

    /**
     * Throws if any comment in the source was never claimed by any {@link #attach} call — i.e. it
     * sits somewhere (e.g. inside an argument list) that Phase 3 doesn't yet know how to position,
     * and printing would otherwise have silently dropped it. Call once, after the whole tree has
     * been visited.
     */
    public void assertAllCommentsClaimed() {
        for (Comment c : comments) {
            if (!claimedTokenIndices.contains(c.tokenIndex())) {
                throw new UnsupportedOperationException(
                        "groovy-formatter: a comment inside a single statement/expression is not supported "
                                + "yet (Phase 3 only preserves comments between statements/members): '"
                                + c.text() + "'");
            }
        }
    }

    /**
     * One printable slot: either a CST node (optionally with a trailing same-line comment) or a
     * standalone comment on its own line.
     */
    public record Item(
            GroovyParser.GroovyParserRuleContext node,
            Comment standaloneComment,
            Comment trailingComment,
            boolean blankBefore) {

        public boolean isNode() {
            return node != null;
        }

        public boolean isComment() {
            return node == null;
        }
    }
}
