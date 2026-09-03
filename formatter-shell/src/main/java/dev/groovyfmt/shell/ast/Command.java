package dev.groovyfmt.shell.ast;

import java.util.List;

/**
 * A "command" in the POSIX grammar sense: a simple command, a compound command, or a function
 * definition. Not a {@link Pipeline} or {@link AndOr} — those wrap a sequence of these.
 */
public sealed interface Command {

    /**
     * @param continuationBeforeWord parallel to {@code words}: {@code true} for a word that followed
     *     an explicit {@code \}-newline line continuation in the source, so the printer can preserve
     *     the author's line break instead of collapsing the command onto one line
     */
    record SimpleCommand(
            List<Assignment> assignments,
            List<Word> words,
            List<Redirection> redirections,
            List<Boolean> continuationBeforeWord)
            implements Command {}

    record Assignment(String name, Word value) {}

    record IfCommand(
            StatementList condition,
            StatementList thenBody,
            List<Elif> elifs,
            StatementList elseBody /* nullable */)
            implements Command {}

    record Elif(StatementList condition, StatementList body) {}

    /** {@code hasIn = false} means {@code for name; do ...} (implicit {@code in "$@"}). */
    record ForCommand(String varName, boolean hasIn, List<Word> items, StatementList body)
            implements Command {}

    record WhileCommand(boolean until, StatementList condition, StatementList body)
            implements Command {}

    record CaseCommand(Word subject, List<CaseItem> items) implements Command {}

    /** {@code terminator} is one of {@code ;; ;& ;;&}. */
    record CaseItem(List<Word> patterns, StatementList body, String terminator) {}

    /** {@code { ...; }} */
    record Group(StatementList body) implements Command {}

    /** {@code ( ... )} */
    record Subshell(StatementList body) implements Command {}

    record FunctionDef(String name, boolean keywordForm, Command body) implements Command {}

    /** A compound command with redirections attached to the compound construct itself, e.g. {@code while ...; do ...; done < file}. */
    record WithRedirections(Command inner, List<Redirection> redirections) implements Command {}
}
