package dev.groovyfmt.shell.lexer;

import dev.groovyfmt.shell.ast.Word;

/**
 * @param fd optional explicit file-descriptor digits immediately preceding a redirect operator
 *     (e.g. the {@code 2} in {@code 2>&1}), or {@code null}
 * @param word set only when {@code type == WORD}
 */
public record Token(TokenType type, String fd, Word word, int line, int column) {

    public static Token of(TokenType type, int line, int column) {
        return new Token(type, null, null, line, column);
    }

    public static Token word(Word word, int line, int column) {
        return new Token(TokenType.WORD, null, word, line, column);
    }
}
