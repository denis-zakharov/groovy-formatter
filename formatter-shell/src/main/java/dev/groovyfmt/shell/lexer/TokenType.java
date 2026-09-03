package dev.groovyfmt.shell.lexer;

public enum TokenType {
    WORD,
    NEWLINE,
    EOF,
    AND_IF, // &&
    OR_IF, // ||
    DSEMI, // ;;
    DSEMI_AMP, // ;&
    DSEMI_DSEMI_AMP, // ;;&
    DLESS, // <<
    DLESSDASH, // <<-
    TLESS, // <<< (here-string)
    DGREAT, // >>
    LESSAND, // <&
    GREATAND, // >&
    LESSGREAT, // <>
    CLOBBER, // >|
    AMPGREAT, // &>
    AMPDGREAT, // &>>
    PIPEAMP, // |&
    PIPE, // |
    AMP, // &
    SEMI, // ;
    LPAREN, // (
    RPAREN, // )
    LESS, // <
    GREAT, // >
}
