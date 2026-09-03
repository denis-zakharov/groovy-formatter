package dev.groovyfmt.shell.parser;

import dev.groovyfmt.shell.ShellParseException;
import dev.groovyfmt.shell.ast.AndOr;
import dev.groovyfmt.shell.ast.Command;
import dev.groovyfmt.shell.ast.Comment;
import dev.groovyfmt.shell.ast.Pipeline;
import dev.groovyfmt.shell.ast.Redirection;
import dev.groovyfmt.shell.ast.Script;
import dev.groovyfmt.shell.ast.Statement;
import dev.groovyfmt.shell.ast.StatementList;
import dev.groovyfmt.shell.ast.Word;
import dev.groovyfmt.shell.ast.WordPart;
import dev.groovyfmt.shell.lexer.Lexer;
import dev.groovyfmt.shell.lexer.Token;
import dev.groovyfmt.shell.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Hand-written recursive-descent parser for a practical POSIX-sh-plus-common-bash subset,
 * feeding off {@link Lexer}. See {@code formatter-shell}'s AGENTS notes for exactly what's
 * in/out of scope. Constructs outside that subset throw {@link UnsupportedOperationException} —
 * never silently drop or mis-parse content, matching the discipline {@code formatter-print}
 * documents for the Groovy side of this project.
 */
public final class Parser {

    private final Lexer lexer;
    private int commentCursor = 0;
    private int lastTokenLine = 1;

    private Parser(Lexer lexer) {
        this.lexer = lexer;
    }

    public static Script parse(String source) {
        Parser p = new Parser(new Lexer(source));
        StatementList list = p.parseStatementList(() -> false);
        Token eof = p.peek();
        if (eof.type() != TokenType.EOF) {
            throw p.error("unexpected token");
        }
        return new Script(list);
    }

    // ---- token helpers ----

    private Token peek() {
        return lexer.peek(0);
    }

    private Token peek(int i) {
        return lexer.peek(i);
    }

    private Token advance() {
        Token t = lexer.advance();
        lastTokenLine = t.line();
        return t;
    }

    private void expect(TokenType type, String what) {
        if (peek().type() != type) {
            throw error("expected " + what);
        }
    }

    private void expectWord(String word) {
        if (!atWord(word)) {
            throw error("expected '" + word + "'");
        }
    }

    private boolean atWord(String s) {
        Token t = peek();
        return t.type() == TokenType.WORD && t.word().isBareLiteral(s);
    }

    private ShellParseException error(String message) {
        Token t = peek();
        return new ShellParseException(message + ", found " + t.type(), t.line(), t.column());
    }

    private void skipNewlinesOnly() {
        while (peek().type() == TokenType.NEWLINE) {
            advance();
        }
    }

    // ---- statement lists, with comment / blank-line bookkeeping ----

    private record Boundary(List<Comment> leadingComments, boolean blankLineBefore) {}

    /**
     * Consumes the NEWLINE tokens (and any comments) between the previous statement's own line
     * and the next real token, and decides whether a blank source line separates them.
     *
     * <p>This counts NEWLINE <em>tokens</em>, not source line numbers, deliberately: a here-doc's
     * opening {@code <<}/{@code <<-} redirect is followed by exactly one NEWLINE token even
     * though the lexer silently swallows several physical source lines (the body plus the
     * delimiter line) while producing it — comparing raw line numbers here would count that gap
     * as a spurious blank line. One NEWLINE token is always "free" (it ends the previous
     * statement's own line, or the block-opening keyword's line on a list's first call); each
     * comment consumes one more (it sits on its own line); anything beyond that is a real blank
     * line.
     */
    private Boundary consumeBoundary() {
        int newlineCount = 0;
        while (peek().type() == TokenType.NEWLINE) {
            advance();
            newlineCount++;
        }
        int nextLine = peek().line();
        List<Comment> leading = new ArrayList<>();
        List<Lexer.LexedComment> all = lexer.comments();
        int commentsInSpan = 0;
        while (commentCursor < all.size() && all.get(commentCursor).line() < nextLine) {
            leading.add(all.get(commentCursor).comment());
            commentCursor++;
            commentsInSpan++;
        }
        boolean blank = (newlineCount - 1 - commentsInSpan) > 0;
        return new Boundary(leading, blank);
    }

    private StatementList parseStatementList(BooleanSupplier isStop) {
        List<StatementList.Entry> entries = new ArrayList<>();
        while (true) {
            Boundary boundary = consumeBoundary();
            if (isStop.getAsBoolean() || peek().type() == TokenType.EOF) {
                return new StatementList(entries, boundary.leadingComments(), boundary.blankLineBefore());
            }
            Statement stmt = parseStatement();
            int stmtEndLine = lastTokenLine;
            Comment trailing = null;
            List<Lexer.LexedComment> all = lexer.comments();
            if (commentCursor < all.size() && all.get(commentCursor).line() == stmtEndLine) {
                trailing = all.get(commentCursor).comment();
                commentCursor++;
            }
            entries.add(
                    new StatementList.Entry(
                            boundary.leadingComments(), boundary.blankLineBefore(), stmt, trailing));
        }
    }

    private Statement parseStatement() {
        AndOr andOr = parseAndOr();
        String terminator = "";
        if (peek().type() == TokenType.SEMI) {
            advance();
            terminator = ";";
        } else if (peek().type() == TokenType.AMP) {
            advance();
            terminator = "&";
        }
        return new Statement(andOr, terminator);
    }

    private AndOr parseAndOr() {
        Pipeline first = parsePipeline();
        List<AndOr.Conjunction> rest = new ArrayList<>();
        while (peek().type() == TokenType.AND_IF || peek().type() == TokenType.OR_IF) {
            String op = peek().type() == TokenType.AND_IF ? "&&" : "||";
            advance();
            skipNewlinesOnly();
            rest.add(new AndOr.Conjunction(op, parsePipeline()));
        }
        return new AndOr(first, rest);
    }

    private Pipeline parsePipeline() {
        boolean negated = false;
        if (atWord("!")) {
            advance();
            negated = true;
        }
        List<Command> commands = new ArrayList<>();
        List<Boolean> pipeStderr = new ArrayList<>();
        commands.add(parseCommand());
        while (peek().type() == TokenType.PIPE || peek().type() == TokenType.PIPEAMP) {
            boolean stderr = peek().type() == TokenType.PIPEAMP;
            advance();
            skipNewlinesOnly();
            pipeStderr.add(stderr);
            commands.add(parseCommand());
        }
        return new Pipeline(negated, commands, pipeStderr);
    }

    // ---- commands ----

    private Command parseCommand() {
        Command cmd = parseCommandInner();
        List<Redirection> extra = new ArrayList<>();
        while (isRedirectStart()) {
            extra.add(parseRedirection());
        }
        if (extra.isEmpty()) {
            return cmd;
        }
        if (cmd instanceof Command.SimpleCommand sc) {
            List<Redirection> merged = new ArrayList<>(sc.redirections());
            merged.addAll(extra);
            return new Command.SimpleCommand(
                    sc.assignments(), sc.words(), merged, sc.continuationBeforeWord());
        }
        return new Command.WithRedirections(cmd, extra);
    }

    private Command parseCommandInner() {
        if (peek().type() == TokenType.WORD) {
            Word w = peek().word();
            if (w.isBareLiteral("if")) {
                return parseIf();
            }
            if (w.isBareLiteral("for")) {
                return parseFor();
            }
            if (w.isBareLiteral("while")) {
                return parseWhile(false);
            }
            if (w.isBareLiteral("until")) {
                return parseWhile(true);
            }
            if (w.isBareLiteral("case")) {
                return parseCase();
            }
            if (w.isBareLiteral("{")) {
                return parseGroup();
            }
            if (w.isBareLiteral("function")) {
                return parseFunctionKeywordForm();
            }
            String literal = literalOrNull(w);
            if (literal != null
                    && List.of("then", "elif", "else", "fi", "do", "done", "esac", "}").contains(literal)) {
                throw error("unexpected reserved word");
            }
            if (isFunctionDefLookahead()) {
                return parseFunctionNoKeyword();
            }
        }
        if (peek().type() == TokenType.LPAREN) {
            return parseSubshell();
        }
        return parseSimpleCommand();
    }

    private static String literalOrNull(Word w) {
        return w.parts().size() == 1 && w.parts().get(0) instanceof WordPart.Literal l ? l.text() : null;
    }

    private boolean isFunctionDefLookahead() {
        return peek().type() == TokenType.WORD
                && peek(1).type() == TokenType.LPAREN
                && peek(2).type() == TokenType.RPAREN;
    }

    private Command parseFunctionNoKeyword() {
        String name = advance().word().plainText();
        advance(); // (
        advance(); // )
        skipNewlinesOnly();
        return new Command.FunctionDef(name, false, parseCommandInner());
    }

    private Command parseFunctionKeywordForm() {
        advance(); // function
        if (peek().type() != TokenType.WORD) {
            throw error("expected function name");
        }
        String name = advance().word().plainText();
        if (peek().type() == TokenType.LPAREN) {
            advance();
            expect(TokenType.RPAREN, ")");
            advance();
        }
        skipNewlinesOnly();
        return new Command.FunctionDef(name, true, parseCommandInner());
    }

    private Command parseGroup() {
        advance(); // {
        StatementList body = parseStatementList(() -> atWord("}"));
        expectWord("}");
        advance();
        return new Command.Group(body);
    }

    private Command parseSubshell() {
        advance(); // (
        StatementList body = parseStatementList(() -> peek().type() == TokenType.RPAREN);
        expect(TokenType.RPAREN, ")");
        advance();
        return new Command.Subshell(body);
    }

    private Command parseIf() {
        advance(); // if
        StatementList cond = parseStatementList(() -> atWord("then"));
        expectWord("then");
        advance();
        StatementList thenBody =
                parseStatementList(() -> atWord("elif") || atWord("else") || atWord("fi"));
        List<Command.Elif> elifs = new ArrayList<>();
        while (atWord("elif")) {
            advance();
            StatementList econd = parseStatementList(() -> atWord("then"));
            expectWord("then");
            advance();
            StatementList ebody =
                    parseStatementList(() -> atWord("elif") || atWord("else") || atWord("fi"));
            elifs.add(new Command.Elif(econd, ebody));
        }
        StatementList elseBody = null;
        if (atWord("else")) {
            advance();
            elseBody = parseStatementList(() -> atWord("fi"));
        }
        expectWord("fi");
        advance();
        return new Command.IfCommand(cond, thenBody, elifs, elseBody);
    }

    private Command parseFor() {
        advance(); // for
        if (peek().type() != TokenType.WORD) {
            throw error("expected name after 'for'");
        }
        String varName = advance().word().plainText();
        skipNewlinesOnly();
        boolean hasIn = false;
        List<Word> items = new ArrayList<>();
        if (atWord("in")) {
            hasIn = true;
            advance();
            while (peek().type() == TokenType.WORD) {
                items.add(advance().word());
            }
            if (peek().type() == TokenType.SEMI) {
                advance();
            }
        } else if (peek().type() == TokenType.SEMI) {
            advance();
        }
        skipNewlinesOnly();
        expectWord("do");
        advance();
        StatementList body = parseStatementList(() -> atWord("done"));
        expectWord("done");
        advance();
        return new Command.ForCommand(varName, hasIn, items, body);
    }

    private Command parseWhile(boolean until) {
        advance(); // while/until
        StatementList cond = parseStatementList(() -> atWord("do"));
        expectWord("do");
        advance();
        StatementList body = parseStatementList(() -> atWord("done"));
        expectWord("done");
        advance();
        return new Command.WhileCommand(until, cond, body);
    }

    private Command parseCase() {
        advance(); // case
        if (peek().type() != TokenType.WORD) {
            throw error("expected word after 'case'");
        }
        Word subject = advance().word();
        skipNewlinesOnly();
        expectWord("in");
        advance();
        skipNewlinesOnly();
        List<Command.CaseItem> items = new ArrayList<>();
        while (!atWord("esac")) {
            if (peek().type() == TokenType.LPAREN) {
                advance();
            }
            List<Word> patterns = new ArrayList<>();
            if (peek().type() != TokenType.WORD) {
                throw error("expected case pattern");
            }
            patterns.add(advance().word());
            while (peek().type() == TokenType.PIPE) {
                advance();
                patterns.add(advance().word());
            }
            expect(TokenType.RPAREN, ")");
            advance();
            skipNewlinesOnly();
            StatementList body =
                    parseStatementList(
                            () ->
                                    peek().type() == TokenType.DSEMI
                                            || peek().type() == TokenType.DSEMI_AMP
                                            || peek().type() == TokenType.DSEMI_DSEMI_AMP
                                            || atWord("esac"));
            String terminator = ";;";
            if (peek().type() == TokenType.DSEMI) {
                advance();
            } else if (peek().type() == TokenType.DSEMI_AMP) {
                terminator = ";&";
                advance();
            } else if (peek().type() == TokenType.DSEMI_DSEMI_AMP) {
                terminator = ";;&";
                advance();
            }
            items.add(new Command.CaseItem(patterns, body, terminator));
            skipNewlinesOnly();
        }
        advance(); // esac
        return new Command.CaseCommand(subject, items);
    }

    // ---- simple commands, assignments, redirections ----

    private Command parseSimpleCommand() {
        List<Command.Assignment> assignments = new ArrayList<>();
        List<Word> words = new ArrayList<>();
        List<Redirection> redirections = new ArrayList<>();
        List<Boolean> continuationBeforeWord = new ArrayList<>();
        int prevLine = peek().line();

        while (true) {
            if (isRedirectStart()) {
                redirections.add(parseRedirection());
                prevLine = lastTokenLine;
                continue;
            }
            if (peek().type() != TokenType.WORD) {
                break;
            }
            if (words.isEmpty()) {
                Command.Assignment assignment = tryParseAssignment();
                if (assignment != null) {
                    assignments.add(assignment);
                    prevLine = lastTokenLine;
                    continue;
                }
            }
            continuationBeforeWord.add(peek().line() != prevLine);
            words.add(advance().word());
            prevLine = lastTokenLine;
        }

        if (assignments.isEmpty() && words.isEmpty() && redirections.isEmpty()) {
            throw error("expected a command");
        }
        return new Command.SimpleCommand(assignments, words, redirections, continuationBeforeWord);
    }

    /** {@code NAME=value}, recognized only while no command word has been seen yet. */
    private Command.Assignment tryParseAssignment() {
        Word w = peek().word();
        if (w.parts().isEmpty() || !(w.parts().get(0) instanceof WordPart.Literal first)) {
            return null;
        }
        int eq = indexOfAssignmentEquals(first.text());
        if (eq < 0) {
            return null;
        }
        String name = first.text().substring(0, eq);
        if (!isValidName(name)) {
            return null;
        }
        advance();
        String rest = first.text().substring(eq + 1);
        if (rest.isEmpty() && w.parts().size() == 1 && peek().type() == TokenType.LPAREN) {
            // '(' is a shell metacharacter, so it can never be part of the Literal text scanned
            // above — NAME=(...) always lexes as a WORD("NAME=") token immediately followed by a
            // separate LPAREN token, which is how we recognize the (unsupported) array-assignment
            // form here instead.
            throw new UnsupportedOperationException("array assignments are not supported: " + name + "=(...)");
        }
        List<WordPart> valueParts = new ArrayList<>();
        if (!rest.isEmpty()) {
            valueParts.add(new WordPart.Literal(rest));
        }
        valueParts.addAll(w.parts().subList(1, w.parts().size()));
        return new Command.Assignment(name, new Word(valueParts));
    }

    private static int indexOfAssignmentEquals(String text) {
        int eq = text.indexOf('=');
        if (eq <= 0) {
            return -1;
        }
        return eq;
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private boolean isRedirectStart() {
        TokenType t = peek().type();
        return t == TokenType.LESS
                || t == TokenType.GREAT
                || t == TokenType.DGREAT
                || t == TokenType.DLESS
                || t == TokenType.DLESSDASH
                || t == TokenType.TLESS
                || t == TokenType.LESSAND
                || t == TokenType.GREATAND
                || t == TokenType.LESSGREAT
                || t == TokenType.CLOBBER
                || t == TokenType.AMPGREAT
                || t == TokenType.AMPDGREAT;
    }

    private Redirection parseRedirection() {
        Token opTok = advance();
        String fd = opTok.fd();
        if (opTok.type() == TokenType.DLESS || opTok.type() == TokenType.DLESSDASH) {
            if (peek().type() != TokenType.WORD) {
                throw error("expected here-doc delimiter");
            }
            Word delim = advance().word();
            boolean strip = opTok.type() == TokenType.DLESSDASH;
            var holder = lexer.registerHereDoc(delim.plainText(), strip);
            return new Redirection.HereDoc(fd, strip ? "<<-" : "<<", delim, holder);
        }
        if (peek().type() != TokenType.WORD) {
            throw error("expected redirection target");
        }
        Word target = advance().word();
        return new Redirection.Redirect(fd, operatorText(opTok.type()), target);
    }

    private static String operatorText(TokenType t) {
        return switch (t) {
            case LESS -> "<";
            case GREAT -> ">";
            case DGREAT -> ">>";
            case TLESS -> "<<<";
            case LESSAND -> "<&";
            case GREATAND -> ">&";
            case LESSGREAT -> "<>";
            case CLOBBER -> ">|";
            case AMPGREAT -> "&>";
            case AMPDGREAT -> "&>>";
            default -> throw new IllegalStateException("not a redirection operator: " + t);
        };
    }
}
