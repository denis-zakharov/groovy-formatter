package dev.groovyfmt.doc;

import static dev.groovyfmt.doc.Docs.HARDLINE;
import static dev.groovyfmt.doc.Docs.LINE;
import static dev.groovyfmt.doc.Docs.concat;
import static dev.groovyfmt.doc.Docs.group;
import static dev.groovyfmt.doc.Docs.ifBreak;
import static dev.groovyfmt.doc.Docs.indent;
import static dev.groovyfmt.doc.Docs.join;
import static dev.groovyfmt.doc.Docs.lineSuffix;
import static dev.groovyfmt.doc.Docs.text;
import static dev.groovyfmt.doc.Docs.SOFTLINE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocRendererTest {

    private static String render(Doc doc, int maxWidth) {
        return DocRenderer.render(doc, new RenderOptions(maxWidth, 2));
    }

    @Test
    void plainTextRendersVerbatim() {
        assertEquals("hello", render(text("hello"), 80));
    }

    @Test
    void concatJoinsPartsWithNoSeparator() {
        Doc doc = concat(text("foo"), text("("), text("bar"), text(")"));
        assertEquals("foo(bar)", render(doc, 80));
    }

    @Test
    void groupThatFitsRendersFlat() {
        Doc args = group(concat(text("("), text("a"), text(","), LINE, text("b"), text(")")));
        assertEquals("(a, b)", render(args, 80));
    }

    @Test
    void groupThatDoesNotFitBreaks() {
        Doc args =
                group(
                        concat(
                                text("("),
                                indent(concat(LINE, text("aVeryLongArgumentName"), text(","), LINE, text("bAlsoLong"))),
                                LINE,
                                text(")")));
        String rendered = render(args, 20);
        assertEquals("(\n  aVeryLongArgumentName,\n  bAlsoLong\n)", rendered);
    }

    @Test
    void hardlineForcesEnclosingGroupToBreakEvenIfItWouldFitFlat() {
        Doc block =
                group(concat(text("{"), indent(concat(HARDLINE, text("x"))), HARDLINE, text("}")));
        assertEquals("{\n  x\n}", render(block, 80));
    }

    @Test
    void nestedGroupWithHardlineForcesOuterGroupToBreakToo() {
        Doc inner = group(concat(text("("), HARDLINE, text(")")));
        Doc outer = group(concat(text("outer("), inner, text(")")));
        // Even though "outer(" + inner-flat + ")" would be short, the inner HardLine means
        // the whole thing can never be rendered as a single line, so the outer group must break too.
        String rendered = render(outer, 80);
        assertEquals("outer((\n))", rendered);
    }

    @Test
    void ifBreakChoosesTrailingCommaOnlyWhenGroupBreaks() {
        Doc trailingComma = ifBreak(text(","), text(""));
        Doc flatDoc =
                group(concat(text("["), indent(concat(SOFTLINE, text("1"), trailingComma)), SOFTLINE, text("]")));
        assertEquals("[1]", render(flatDoc, 80));

        Doc brokenDoc =
                group(
                        concat(
                                text("["),
                                indent(concat(SOFTLINE, text("aVeryLongElementNameHere"), trailingComma)),
                                SOFTLINE,
                                text("]")));
        assertEquals("[\n  aVeryLongElementNameHere,\n]", render(brokenDoc, 10));
    }

    @Test
    void lineSuffixDefersContentToBeforeNextNewlineWithoutForcingGroupBreak() {
        Doc doc = group(concat(text("x = 1"), lineSuffix(text(" // trailing")), HARDLINE, text("y = 2")));
        assertEquals("x = 1 // trailing\ny = 2", render(doc, 80));
    }

    @Test
    void lineSuffixAtEndOfDocumentStillFlushes() {
        Doc doc = concat(text("x = 1"), lineSuffix(text(" // trailing comment, no more lines after")));
        assertEquals("x = 1 // trailing comment, no more lines after", render(doc, 80));
    }

    @Test
    void joinInsertsSeparatorBetweenEachPair() {
        Doc doc = join(text(", "), List.of(text("a"), text("b"), text("c")));
        assertEquals("a, b, c", render(doc, 80));
    }

    @Test
    void indentAppliesOnlyAfterNewlinesProducedInsideIt() {
        Doc doc = concat(text("a"), indent(concat(HARDLINE, text("b"), HARDLINE, text("c"))), HARDLINE, text("d"));
        assertEquals("a\n  b\n  c\nd", render(doc, 80));
    }

    @Test
    void independentGroupsEachDecideFitIndependently() {
        // First group is short and fits; second is long and must break, even on the same doc.
        Doc first = group(concat(text("("), LINE, text("a"), LINE, text(")")));
        Doc second =
                group(
                        concat(
                                text("("),
                                indent(concat(LINE, text("aVeryLongArgumentThatWontFit"))),
                                LINE,
                                text(")")));
        Doc doc = concat(first, text(" "), second);
        assertEquals("( a ) (\n  aVeryLongArgumentThatWontFit\n)", render(doc, 15));
    }
}
