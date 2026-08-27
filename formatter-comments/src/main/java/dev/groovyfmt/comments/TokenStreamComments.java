package dev.groovyfmt.comments;

import groovyjarjarantlr4.v4.runtime.CommonTokenStream;
import groovyjarjarantlr4.v4.runtime.Token;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts comments from a Groovy token stream.
 *
 * <p>Groovy's lexer rewrites both {@code //} and {@code /* *}{@code /} comments' token <em>type</em>
 * to {@code NL} (see {@code GroovyLexer.g4}: {@code ML_COMMENT}/{@code SL_COMMENT} both {@code
 * -> type(NL)}), and places them on either the default or hidden channel depending on
 * paren-nesting. So comments must be found by inspecting each token's raw <em>text</em>, never its
 * type or channel — confirmed against the real grammar/jar, not assumed.
 */
public final class TokenStreamComments {

    private TokenStreamComments() {}

    public static List<Comment> extract(CommonTokenStream tokens) {
        List<Comment> comments = new ArrayList<>();
        for (Token token : tokens.getTokens()) {
            String text = token.getText();
            if (text == null) {
                continue;
            }
            if (text.startsWith("//")) {
                comments.add(new Comment(text, Comment.Kind.LINE, token.getLine(), token.getTokenIndex()));
            } else if (text.startsWith("/**") && text.length() > 4) {
                comments.add(new Comment(text, Comment.Kind.GROOVYDOC, token.getLine(), token.getTokenIndex()));
            } else if (text.startsWith("/*")) {
                comments.add(new Comment(text, Comment.Kind.BLOCK, token.getLine(), token.getTokenIndex()));
            }
        }
        return comments;
    }
}
