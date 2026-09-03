package dev.groovyfmt.shell.print;

import dev.groovyfmt.doc.Doc;
import dev.groovyfmt.doc.Docs;
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
import dev.groovyfmt.shell.parser.Parser;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks the shell AST and emits a {@link Doc} tree. Every statement list is rendered with one
 * statement per line (hardline-separated) — real shell scripts (and this project's existing
 * Jenkinsfile {@code sh} blocks) are conventionally formatted that way, and it sidesteps needing
 * a flat/broken dual-mode renderer for blocks; the one place that still benefits from staying on
 * a single line when short — a {@code $(...)} command substitution with exactly one statement —
 * falls out for free, since hardlines are only inserted *between* entries.
 */
public final class ShellPrinter {

    public Doc print(Script script) {
        return printStatementList(script.statements());
    }

    Doc printStatementList(StatementList list) {
        List<Doc> lines = new ArrayList<>();
        boolean first = true;
        for (StatementList.Entry entry : list.entries()) {
            if (!first) {
                lines.add(Docs.HARDLINE);
            }
            if (entry.blankLineBefore()) {
                lines.add(Docs.HARDLINE);
            }
            for (Comment c : entry.leadingComments()) {
                lines.add(Docs.text(c.text()));
                lines.add(Docs.HARDLINE);
            }
            Doc stmtDoc = printStatement(entry.statement());
            if (entry.trailingComment() != null) {
                stmtDoc = Docs.concat(stmtDoc, Docs.lineSuffix(Docs.text(" " + entry.trailingComment().text())));
            }
            lines.add(stmtDoc);
            first = false;
        }
        if (!list.danglingComments().isEmpty()) {
            if (!first) {
                lines.add(Docs.HARDLINE);
                if (list.blankLineBeforeDangling()) {
                    lines.add(Docs.HARDLINE);
                }
            }
            boolean firstDangling = true;
            for (Comment c : list.danglingComments()) {
                if (!firstDangling) {
                    lines.add(Docs.HARDLINE);
                }
                lines.add(Docs.text(c.text()));
                firstDangling = false;
            }
        }
        return Docs.concat(lines);
    }

    private Doc printStatement(Statement stmt) {
        Doc core = printAndOr(stmt.andOr());
        Doc term = "&".equals(stmt.terminator()) ? Docs.text(" &") : Docs.NIL;
        List<Doc> parts = new ArrayList<>();
        parts.add(core);
        parts.add(term);
        for (Redirection.HereDoc hd : collectHereDocs(stmt.andOr())) {
            parts.add(Docs.HARDLINE);
            String body = hd.body();
            if (!body.isEmpty()) {
                for (String line : body.split("\n", -1)) {
                    parts.add(Docs.text(line));
                    parts.add(Docs.HARDLINE);
                }
            }
            parts.add(printWord(hd.delimiter()));
        }
        return Docs.concat(parts);
    }

    private static List<Redirection.HereDoc> collectHereDocs(AndOr andOr) {
        List<Redirection.HereDoc> result = new ArrayList<>();
        collectHereDocs(andOr.first(), result);
        for (AndOr.Conjunction c : andOr.rest()) {
            collectHereDocs(c.pipeline(), result);
        }
        return result;
    }

    private static void collectHereDocs(Pipeline p, List<Redirection.HereDoc> out) {
        for (Command c : p.commands()) {
            collectHereDocs(c, out);
        }
    }

    private static void collectHereDocs(Command c, List<Redirection.HereDoc> out) {
        if (c instanceof Command.SimpleCommand sc) {
            for (Redirection r : sc.redirections()) {
                if (r instanceof Redirection.HereDoc hd) {
                    out.add(hd);
                }
            }
        } else if (c instanceof Command.WithRedirections wr) {
            collectHereDocs(wr.inner(), out);
            for (Redirection r : wr.redirections()) {
                if (r instanceof Redirection.HereDoc hd) {
                    out.add(hd);
                }
            }
        }
        // Other compound commands' own bodies collect and print their heredocs themselves,
        // when their own nested statement list is printed.
    }

    private Doc printAndOr(AndOr andOr) {
        List<Doc> parts = new ArrayList<>();
        parts.add(printPipeline(andOr.first()));
        for (AndOr.Conjunction c : andOr.rest()) {
            parts.add(Docs.text(" " + c.operator() + " "));
            parts.add(printPipeline(c.pipeline()));
        }
        return Docs.concat(parts);
    }

    private Doc printPipeline(Pipeline p) {
        List<Doc> parts = new ArrayList<>();
        if (p.negated()) {
            parts.add(Docs.text("! "));
        }
        for (int i = 0; i < p.commands().size(); i++) {
            if (i > 0) {
                parts.add(Docs.text(p.pipeStderr().get(i - 1) ? " |& " : " | "));
            }
            parts.add(printCommand(p.commands().get(i)));
        }
        return Docs.concat(parts);
    }

    private Doc printCommand(Command c) {
        if (c instanceof Command.SimpleCommand sc) {
            return printSimpleCommand(sc);
        } else if (c instanceof Command.IfCommand ic) {
            return printIf(ic);
        } else if (c instanceof Command.ForCommand fc) {
            return printFor(fc);
        } else if (c instanceof Command.WhileCommand wc) {
            return printWhile(wc);
        } else if (c instanceof Command.CaseCommand cc) {
            return printCase(cc);
        } else if (c instanceof Command.Group g) {
            return printGroup(g);
        } else if (c instanceof Command.Subshell s) {
            return printSubshell(s);
        } else if (c instanceof Command.FunctionDef f) {
            return printFunctionDef(f);
        } else if (c instanceof Command.WithRedirections wr) {
            return printWithRedirections(wr);
        }
        throw new IllegalStateException("unhandled command type: " + c.getClass());
    }

    private Doc printSimpleCommand(Command.SimpleCommand sc) {
        List<Doc> parts = new ArrayList<>();
        boolean first = true;
        for (Command.Assignment a : sc.assignments()) {
            if (!first) {
                parts.add(Docs.text(" "));
            }
            parts.add(Docs.text(a.name() + "="));
            parts.add(printWord(a.value()));
            first = false;
        }
        List<Word> words = sc.words();
        List<Boolean> continuationBeforeWord = sc.continuationBeforeWord();
        for (int i = 0; i < words.size(); i++) {
            if (!first) {
                if (i < continuationBeforeWord.size() && continuationBeforeWord.get(i)) {
                    parts.add(Docs.text(" \\"));
                    parts.add(Docs.indent(Docs.HARDLINE));
                } else {
                    parts.add(Docs.text(" "));
                }
            }
            parts.add(printWord(words.get(i)));
            first = false;
        }
        for (Redirection r : sc.redirections()) {
            if (!first) {
                parts.add(Docs.text(" "));
            }
            parts.add(printRedirection(r));
            first = false;
        }
        return Docs.concat(parts);
    }

    private Doc printRedirection(Redirection r) {
        if (r instanceof Redirection.Redirect rd) {
            String fdText = rd.fd() == null ? "" : rd.fd();
            boolean tight = "<&".equals(rd.operator()) || ">&".equals(rd.operator());
            return Docs.concat(
                    Docs.text(fdText + rd.operator() + (tight ? "" : " ")), printWord(rd.target()));
        }
        Redirection.HereDoc hd = (Redirection.HereDoc) r;
        String fdText = hd.fd() == null ? "" : hd.fd();
        return Docs.concat(Docs.text(fdText + hd.operator()), printWord(hd.delimiter()));
    }

    /**
     * Renders a condition/list that's expected to stay on the same physical line as the keyword
     * following it ({@code then}/{@code do}), e.g. {@code if cond; then}. Falls back to one
     * statement per line (with {@code keyword} on its own trailing line) when the list is
     * anything other than the common single-statement, comment-free case, to avoid mangling
     * comments or blank lines into a misleading single line.
     */
    private Doc printHeaderList(StatementList list, String keyword) {
        if (list.entries().size() == 1 && list.danglingComments().isEmpty()) {
            StatementList.Entry e = list.entries().get(0);
            if (e.leadingComments().isEmpty() && !e.blankLineBefore() && e.trailingComment() == null) {
                Statement s = e.statement();
                String bg = "&".equals(s.terminator()) ? " &" : "";
                return Docs.concat(printAndOr(s.andOr()), Docs.text(bg + "; " + keyword));
            }
        }
        return Docs.concat(printStatementList(list), Docs.HARDLINE, Docs.text(keyword));
    }

    /** {@code header} then a hardline-newline, {@code body} indented one level, then a hardline. */
    private static Doc indentedBlock(Doc body) {
        return Docs.indent(Docs.concat(Docs.HARDLINE, body));
    }

    private Doc printIf(Command.IfCommand ic) {
        List<Doc> parts = new ArrayList<>();
        parts.add(Docs.text("if "));
        parts.add(printHeaderList(ic.condition(), "then"));
        parts.add(indentedBlock(printStatementList(ic.thenBody())));
        for (Command.Elif e : ic.elifs()) {
            parts.add(Docs.HARDLINE);
            parts.add(Docs.text("elif "));
            parts.add(printHeaderList(e.condition(), "then"));
            parts.add(indentedBlock(printStatementList(e.body())));
        }
        if (ic.elseBody() != null) {
            parts.add(Docs.HARDLINE);
            parts.add(Docs.text("else"));
            parts.add(indentedBlock(printStatementList(ic.elseBody())));
        }
        parts.add(Docs.HARDLINE);
        parts.add(Docs.text("fi"));
        return Docs.concat(parts);
    }

    private Doc printFor(Command.ForCommand fc) {
        List<Doc> parts = new ArrayList<>();
        parts.add(Docs.text("for " + fc.varName()));
        if (fc.hasIn()) {
            parts.add(Docs.text(" in"));
            for (Word w : fc.items()) {
                parts.add(Docs.text(" "));
                parts.add(printWord(w));
            }
        }
        parts.add(Docs.text("; do"));
        parts.add(indentedBlock(printStatementList(fc.body())));
        parts.add(Docs.HARDLINE);
        parts.add(Docs.text("done"));
        return Docs.concat(parts);
    }

    private Doc printWhile(Command.WhileCommand wc) {
        List<Doc> parts = new ArrayList<>();
        parts.add(Docs.text(wc.until() ? "until " : "while "));
        parts.add(printHeaderList(wc.condition(), "do"));
        parts.add(indentedBlock(printStatementList(wc.body())));
        parts.add(Docs.HARDLINE);
        parts.add(Docs.text("done"));
        return Docs.concat(parts);
    }

    private Doc printCase(Command.CaseCommand cc) {
        List<Doc> parts = new ArrayList<>();
        parts.add(Docs.text("case "));
        parts.add(printWord(cc.subject()));
        parts.add(Docs.text(" in"));
        for (Command.CaseItem item : cc.items()) {
            parts.add(indentedBlock(printCaseItem(item)));
        }
        parts.add(Docs.HARDLINE);
        parts.add(Docs.text("esac"));
        return Docs.concat(parts);
    }

    private Doc printCaseItem(Command.CaseItem item) {
        List<Doc> parts = new ArrayList<>();
        for (int i = 0; i < item.patterns().size(); i++) {
            if (i > 0) {
                parts.add(Docs.text(" | "));
            }
            parts.add(printWord(item.patterns().get(i)));
        }
        parts.add(Docs.text(")"));
        List<Doc> bodyParts = new ArrayList<>();
        if (!item.body().isEmpty()) {
            bodyParts.add(printStatementList(item.body()));
            bodyParts.add(Docs.HARDLINE);
        }
        bodyParts.add(Docs.text(item.terminator()));
        parts.add(indentedBlock(Docs.concat(bodyParts)));
        return Docs.concat(parts);
    }

    private Doc printGroup(Command.Group g) {
        return Docs.concat(
                Docs.text("{"), indentedBlock(printStatementList(g.body())), Docs.HARDLINE, Docs.text("}"));
    }

    private Doc printSubshell(Command.Subshell s) {
        return Docs.concat(
                Docs.text("("), indentedBlock(printStatementList(s.body())), Docs.HARDLINE, Docs.text(")"));
    }

    private Doc printFunctionDef(Command.FunctionDef f) {
        String prefix = (f.keywordForm() ? "function " : "") + f.name() + "() ";
        return Docs.concat(Docs.text(prefix), printCommand(f.body()));
    }

    private Doc printWithRedirections(Command.WithRedirections wr) {
        List<Doc> parts = new ArrayList<>();
        parts.add(printCommand(wr.inner()));
        for (Redirection r : wr.redirections()) {
            parts.add(Docs.text(" "));
            parts.add(printRedirection(r));
        }
        return Docs.concat(parts);
    }

    private Doc printWord(Word w) {
        List<Doc> parts = new ArrayList<>();
        for (WordPart p : w.parts()) {
            parts.add(printWordPart(p));
        }
        return Docs.concat(parts);
    }

    private Doc printWordParts(List<WordPart> parts) {
        List<Doc> docs = new ArrayList<>();
        for (WordPart p : parts) {
            docs.add(printWordPart(p));
        }
        return Docs.concat(docs);
    }

    private Doc printWordPart(WordPart p) {
        if (p instanceof WordPart.Literal l) {
            return Docs.text(l.text());
        } else if (p instanceof WordPart.SingleQuoted q) {
            return Docs.text("'" + q.raw() + "'");
        } else if (p instanceof WordPart.AnsiCQuoted q) {
            return Docs.text("$'" + q.raw() + "'");
        } else if (p instanceof WordPart.DoubleQuoted q) {
            return Docs.concat(Docs.text("\""), printWordParts(q.parts()), Docs.text("\""));
        } else if (p instanceof WordPart.LocaleQuoted q) {
            return Docs.concat(Docs.text("$\""), printWordParts(q.parts()), Docs.text("\""));
        } else if (p instanceof WordPart.ParamExpansion pe) {
            return Docs.text("$" + pe.raw());
        } else if (p instanceof WordPart.ArithExpansion ae) {
            return Docs.text("$((" + ae.raw() + "))");
        } else if (p instanceof WordPart.CommandSubstitution cs) {
            return printCommandSubstitution(cs);
        }
        throw new IllegalStateException("unhandled word part type: " + p.getClass());
    }

    private Doc printCommandSubstitution(WordPart.CommandSubstitution cs) {
        if (cs.backtick()) {
            return Docs.text("`" + cs.raw() + "`");
        }
        try {
            Script nested = Parser.parse(cs.raw());
            Doc body = printStatementList(nested.statements());
            return Docs.concat(Docs.text("$("), body, Docs.text(")"));
        } catch (RuntimeException e) {
            return Docs.text("$(" + cs.raw().strip() + ")");
        }
    }
}
