package dev.groovyfmt.shell.lexer;

import dev.groovyfmt.shell.ShellParseException;
import dev.groovyfmt.shell.ast.Comment;
import dev.groovyfmt.shell.ast.HereDocBody;
import dev.groovyfmt.shell.ast.Word;
import dev.groovyfmt.shell.ast.WordPart;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Hand-written shell tokenizer. There is no off-the-shelf shell grammar to reuse the way
 * {@code formatter-parser} reuses Groovy's own ANTLR grammar — this lexer (and the parser built
 * on it) implement a practical POSIX-sh-plus-common-bash subset from scratch. See
 * {@code formatter-shell}'s package docs / AGENTS.md for exactly what's in and out of scope.
 *
 * <p>Every {@code Literal} run and every quoted/expansion body is kept as the exact source
 * substring, never reinterpreted — this is a structural formatter, not a content normalizer.
 */
public final class Lexer {

    private final String src;
    private final int len;
    private int pos = 0;
    private int line = 1;
    private int col = 1;

    private final Deque<Registration> pendingHereDocs = new ArrayDeque<>();
    private final List<LexedComment> comments = new ArrayList<>();
    private final List<Token> lookahead = new ArrayList<>();

    public Lexer(String src) {
        this.src = src;
        this.len = src.length();
    }

    public record LexedComment(int line, Comment comment) {}

    private record Registration(String delimiter, boolean stripLeadingTabs, HereDocBody holder) {}

    /** All comments encountered so far, in source order. Grows as tokens are peeked/consumed. */
    public List<LexedComment> comments() {
        return comments;
    }

    /**
     * Called by the parser immediately after consuming a {@code <<}/{@code <<-} operator and its
     * delimiter word. Must be called before the parser looks further ahead than the current
     * line, since the body is read in as soon as the lexer crosses the next newline. The
     * returned holder's {@code text} is filled in at that point.
     */
    public HereDocBody registerHereDoc(String delimiter, boolean stripLeadingTabs) {
        HereDocBody holder = new HereDocBody();
        pendingHereDocs.add(new Registration(delimiter, stripLeadingTabs, holder));
        return holder;
    }

    public Token peek() {
        return peek(0);
    }

    public Token peek(int i) {
        while (lookahead.size() <= i) {
            lookahead.add(scanNext());
        }
        return lookahead.get(i);
    }

    public Token advance() {
        Token t = peek(0);
        lookahead.remove(0);
        return t;
    }

    // ---- character-level plumbing ----

    private char charAt(int p) {
        return src.charAt(p);
    }

    private void advanceChar() {
        if (src.charAt(pos) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        pos++;
    }

    private ShellParseException error(String message) {
        return new ShellParseException(message, line, col);
    }

    // ---- token scanning ----

    private Token scanNext() {
        skipWhitespaceAndComments();
        int startLine = line;
        int startCol = col;
        if (pos >= len) {
            return Token.of(TokenType.EOF, startLine, startCol);
        }
        char c = charAt(pos);

        if (c == '\n') {
            advanceChar();
            if (!pendingHereDocs.isEmpty()) {
                readHereDocBodies();
            }
            return Token.of(TokenType.NEWLINE, startLine, startCol);
        }

        String fd = null;
        if (Character.isDigit(c)) {
            int digitsEnd = pos;
            while (digitsEnd < len && Character.isDigit(charAt(digitsEnd))) {
                digitsEnd++;
            }
            if (digitsEnd < len && (charAt(digitsEnd) == '<' || charAt(digitsEnd) == '>')) {
                StringBuilder digits = new StringBuilder();
                while (pos < digitsEnd) {
                    digits.append(charAt(pos));
                    advanceChar();
                }
                fd = digits.toString();
                c = charAt(pos);
            }
        }

        TokenType type = scanOperatorOrNull(c);
        if (type != null) {
            return new Token(type, fd, null, startLine, startCol);
        }
        if (fd != null) {
            // Digits were consumed speculatively as an fd prefix but turned out not to precede
            // a redirection operator after all — unreachable given the lookahead check above,
            // kept as a defensive guard.
            throw error("expected redirection operator after file descriptor " + fd);
        }

        List<WordPart> parts = scanWord(false);
        if (parts.isEmpty()) {
            throw error("unexpected character '" + c + "'");
        }
        return Token.word(new Word(parts), startLine, startCol);
    }

    private TokenType scanOperatorOrNull(char c) {
        switch (c) {
            case ';':
                if (peekChar(1) == '&') {
                    advanceChar();
                    advanceChar();
                    return TokenType.DSEMI_AMP;
                }
                if (peekChar(1) == ';') {
                    if (peekChar(2) == '&') {
                        advanceChar();
                        advanceChar();
                        advanceChar();
                        return TokenType.DSEMI_DSEMI_AMP;
                    }
                    advanceChar();
                    advanceChar();
                    return TokenType.DSEMI;
                }
                advanceChar();
                return TokenType.SEMI;
            case '&':
                if (peekChar(1) == '&') {
                    advanceChar();
                    advanceChar();
                    return TokenType.AND_IF;
                }
                if (peekChar(1) == '>') {
                    if (peekChar(2) == '>') {
                        advanceChar();
                        advanceChar();
                        advanceChar();
                        return TokenType.AMPDGREAT;
                    }
                    advanceChar();
                    advanceChar();
                    return TokenType.AMPGREAT;
                }
                advanceChar();
                return TokenType.AMP;
            case '|':
                if (peekChar(1) == '|') {
                    advanceChar();
                    advanceChar();
                    return TokenType.OR_IF;
                }
                if (peekChar(1) == '&') {
                    advanceChar();
                    advanceChar();
                    return TokenType.PIPEAMP;
                }
                advanceChar();
                return TokenType.PIPE;
            case '<':
                if (peekChar(1) == '<') {
                    if (peekChar(2) == '-') {
                        advanceChar();
                        advanceChar();
                        advanceChar();
                        return TokenType.DLESSDASH;
                    }
                    if (peekChar(2) == '<') {
                        advanceChar();
                        advanceChar();
                        advanceChar();
                        return TokenType.TLESS;
                    }
                    advanceChar();
                    advanceChar();
                    return TokenType.DLESS;
                }
                if (peekChar(1) == '&') {
                    advanceChar();
                    advanceChar();
                    return TokenType.LESSAND;
                }
                if (peekChar(1) == '>') {
                    advanceChar();
                    advanceChar();
                    return TokenType.LESSGREAT;
                }
                advanceChar();
                return TokenType.LESS;
            case '>':
                if (peekChar(1) == '>') {
                    advanceChar();
                    advanceChar();
                    return TokenType.DGREAT;
                }
                if (peekChar(1) == '&') {
                    advanceChar();
                    advanceChar();
                    return TokenType.GREATAND;
                }
                if (peekChar(1) == '|') {
                    advanceChar();
                    advanceChar();
                    return TokenType.CLOBBER;
                }
                advanceChar();
                return TokenType.GREAT;
            case '(':
                advanceChar();
                return TokenType.LPAREN;
            case ')':
                advanceChar();
                return TokenType.RPAREN;
            default:
                return null;
        }
    }

    private char peekChar(int offset) {
        int p = pos + offset;
        return p < len ? charAt(p) : '\0';
    }

    private void skipWhitespaceAndComments() {
        while (pos < len) {
            char c = charAt(pos);
            if (c == ' ' || c == '\t') {
                advanceChar();
                continue;
            }
            if (c == '\\' && peekChar(1) == '\n') {
                advanceChar();
                advanceChar();
                continue;
            }
            if (c == '#') {
                int commentLine = line;
                int start = pos;
                while (pos < len && charAt(pos) != '\n') {
                    advanceChar();
                }
                comments.add(new LexedComment(commentLine, new Comment(src.substring(start, pos))));
                continue;
            }
            break;
        }
    }

    private void readHereDocBodies() {
        List<Registration> docs = new ArrayList<>(pendingHereDocs);
        pendingHereDocs.clear();
        for (Registration doc : docs) {
            StringBuilder body = new StringBuilder();
            boolean first = true;
            while (true) {
                int lineStart = pos;
                while (pos < len && charAt(pos) != '\n') {
                    advanceChar();
                }
                String rawLine = src.substring(lineStart, pos);
                boolean hadNewline = pos < len;
                String compareLine = doc.stripLeadingTabs() ? stripLeadingTabs(rawLine) : rawLine;
                if (compareLine.equals(doc.delimiter())) {
                    if (hadNewline) {
                        advanceChar();
                    }
                    break;
                }
                if (!first) {
                    body.append('\n');
                }
                body.append(rawLine);
                first = false;
                if (!hadNewline) {
                    break;
                }
                advanceChar();
            }
            doc.holder().text = body.toString();
        }
    }

    private static String stripLeadingTabs(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '\t') {
            i++;
        }
        return s.substring(i);
    }

    // ---- word / quoting / expansion scanning ----

    private List<WordPart> scanWord(boolean insideDouble) {
        List<WordPart> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        while (pos < len) {
            char c = charAt(pos);
            if (insideDouble) {
                if (c == '"') {
                    break;
                }
            } else if (isWhitespaceOrNewline(c) || isMetachar(c)) {
                break;
            }

            if (c == '\\') {
                if (pos + 1 < len && charAt(pos + 1) == '\n') {
                    advanceChar();
                    advanceChar();
                    continue;
                }
                lit.append(c);
                advanceChar();
                if (pos < len) {
                    lit.append(charAt(pos));
                    advanceChar();
                }
                continue;
            }
            if (!insideDouble && c == '\'') {
                flush(lit, parts);
                advanceChar();
                int start = pos;
                while (pos < len && charAt(pos) != '\'') {
                    advanceChar();
                }
                if (pos >= len) {
                    throw error("unterminated '...'");
                }
                String raw = src.substring(start, pos);
                advanceChar();
                parts.add(new WordPart.SingleQuoted(raw));
                continue;
            }
            if (!insideDouble && c == '"') {
                flush(lit, parts);
                advanceChar();
                List<WordPart> inner = scanWord(true);
                if (pos >= len || charAt(pos) != '"') {
                    throw error("unterminated \"...\"");
                }
                advanceChar();
                parts.add(new WordPart.DoubleQuoted(inner));
                continue;
            }
            if (c == '`') {
                flush(lit, parts);
                advanceChar();
                String raw = scanBacktick();
                parts.add(new WordPart.CommandSubstitution(raw, true));
                continue;
            }
            if (c == '$') {
                flush(lit, parts);
                parts.add(scanDollar());
                continue;
            }
            lit.append(c);
            advanceChar();
        }
        flush(lit, parts);
        return parts;
    }

    private static void flush(StringBuilder lit, List<WordPart> parts) {
        if (lit.length() > 0) {
            parts.add(new WordPart.Literal(lit.toString()));
            lit.setLength(0);
        }
    }

    private static boolean isWhitespaceOrNewline(char c) {
        return c == ' ' || c == '\t' || c == '\n';
    }

    private static boolean isMetachar(char c) {
        return c == '|' || c == '&' || c == ';' || c == '(' || c == ')' || c == '<' || c == '>';
    }

    private WordPart scanDollar() {
        advanceChar(); // consume '$'
        if (pos >= len) {
            return new WordPart.Literal("$");
        }
        char c = charAt(pos);
        if (c == '\'') {
            advanceChar();
            int start = pos;
            while (pos < len && charAt(pos) != '\'') {
                if (charAt(pos) == '\\' && pos + 1 < len) {
                    advanceChar();
                }
                advanceChar();
            }
            if (pos >= len) {
                throw error("unterminated $'...'");
            }
            String raw = src.substring(start, pos);
            advanceChar();
            return new WordPart.AnsiCQuoted(raw);
        }
        if (c == '"') {
            advanceChar();
            List<WordPart> inner = scanWord(true);
            if (pos >= len || charAt(pos) != '"') {
                throw error("unterminated $\"...\"");
            }
            advanceChar();
            return new WordPart.LocaleQuoted(inner);
        }
        if (c == '(') {
            if (peekChar(1) == '(') {
                advanceChar();
                advanceChar();
                return new WordPart.ArithExpansion(scanBalancedArith());
            }
            advanceChar();
            return new WordPart.CommandSubstitution(scanBalancedParen(), false);
        }
        if (c == '{') {
            advanceChar();
            return new WordPart.ParamExpansion("{" + scanBalancedBrace() + "}");
        }
        if (isSpecialParamChar(c) || Character.isDigit(c)) {
            advanceChar();
            return new WordPart.ParamExpansion(String.valueOf(c));
        }
        if (Character.isLetter(c) || c == '_') {
            int start = pos;
            while (pos < len && (Character.isLetterOrDigit(charAt(pos)) || charAt(pos) == '_')) {
                advanceChar();
            }
            return new WordPart.ParamExpansion(src.substring(start, pos));
        }
        return new WordPart.Literal("$");
    }

    private static boolean isSpecialParamChar(char c) {
        return c == '?' || c == '#' || c == '@' || c == '*' || c == '!' || c == '$' || c == '-';
    }

    private String scanBalancedParen() {
        int depth = 1;
        int start = pos;
        while (pos < len) {
            char c = charAt(pos);
            if (c == '\\' && pos + 1 < len) {
                advanceChar();
                advanceChar();
                continue;
            }
            if (c == '\'') {
                advanceChar();
                while (pos < len && charAt(pos) != '\'') {
                    advanceChar();
                }
                if (pos < len) {
                    advanceChar();
                }
                continue;
            }
            if (c == '"') {
                advanceChar();
                while (pos < len && charAt(pos) != '"') {
                    if (charAt(pos) == '\\' && pos + 1 < len) {
                        advanceChar();
                    }
                    advanceChar();
                }
                if (pos < len) {
                    advanceChar();
                }
                continue;
            }
            if (c == '(') {
                depth++;
                advanceChar();
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth == 0) {
                    String raw = src.substring(start, pos);
                    advanceChar();
                    return raw;
                }
                advanceChar();
                continue;
            }
            advanceChar();
        }
        throw error("unterminated $(...)");
    }

    private String scanBalancedArith() {
        int depth = 0;
        int start = pos;
        while (pos < len) {
            char c = charAt(pos);
            if (c == '(') {
                depth++;
                advanceChar();
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                    advanceChar();
                    continue;
                }
                if (peekChar(1) == ')') {
                    String raw = src.substring(start, pos);
                    advanceChar();
                    advanceChar();
                    return raw;
                }
                advanceChar();
                continue;
            }
            advanceChar();
        }
        throw error("unterminated $((...))");
    }

    private String scanBalancedBrace() {
        int depth = 1;
        int start = pos;
        while (pos < len) {
            char c = charAt(pos);
            if (c == '\\' && pos + 1 < len) {
                advanceChar();
                advanceChar();
                continue;
            }
            if (c == '\'') {
                advanceChar();
                while (pos < len && charAt(pos) != '\'') {
                    advanceChar();
                }
                if (pos < len) {
                    advanceChar();
                }
                continue;
            }
            if (c == '"') {
                advanceChar();
                while (pos < len && charAt(pos) != '"') {
                    if (charAt(pos) == '\\' && pos + 1 < len) {
                        advanceChar();
                    }
                    advanceChar();
                }
                if (pos < len) {
                    advanceChar();
                }
                continue;
            }
            if (c == '{') {
                depth++;
                advanceChar();
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    String raw = src.substring(start, pos);
                    advanceChar();
                    return raw;
                }
                advanceChar();
                continue;
            }
            advanceChar();
        }
        throw error("unterminated ${...}");
    }

    private String scanBacktick() {
        int start = pos;
        while (pos < len) {
            char c = charAt(pos);
            if (c == '\\' && pos + 1 < len) {
                advanceChar();
                advanceChar();
                continue;
            }
            if (c == '`') {
                String raw = src.substring(start, pos);
                advanceChar();
                return raw;
            }
            advanceChar();
        }
        throw error("unterminated `...`");
    }
}
