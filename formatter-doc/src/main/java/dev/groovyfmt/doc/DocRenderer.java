package dev.groovyfmt.doc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link Doc} tree to text. Each {@link Doc.Group} independently decides whether it
 * fits flat on the remainder of the current line (accounting for content that follows it, up to
 * the next forced newline) or must render broken.
 */
public final class DocRenderer {

    private DocRenderer() {}

    private enum Mode { FLAT, BREAK }

    private record Cmd(int indent, Mode mode, Doc doc) {}

    public static String render(Doc doc, RenderOptions options) {
        Map<Doc, Boolean> breakCache = new IdentityHashMap<>();
        StringBuilder out = new StringBuilder();
        Deque<Cmd> stack = new ArrayDeque<>();
        List<Cmd> lineSuffixes = new ArrayList<>();
        stack.push(new Cmd(0, Mode.BREAK, doc));

        int pos = 0;
        while (!stack.isEmpty()) {
            Cmd cmd = stack.pop();
            Doc d = cmd.doc();

            if (d instanceof Doc.Text t) {
                out.append(t.value());
                pos += t.value().length();
            } else if (d instanceof Doc.Concat c) {
                pushChildrenReversed(stack, cmd.indent(), cmd.mode(), c.parts());
            } else if (d instanceof Doc.Indent ind) {
                stack.push(new Cmd(cmd.indent() + 1, cmd.mode(), ind.child()));
            } else if (d instanceof Doc.Group g) {
                boolean forced = containsForcedBreak(g.child(), breakCache);
                Mode mode;
                if (forced) {
                    mode = Mode.BREAK;
                } else if (cmd.mode() == Mode.FLAT) {
                    mode = Mode.FLAT;
                } else {
                    Cmd flatNext = new Cmd(cmd.indent(), Mode.FLAT, g.child());
                    mode = fits(flatNext, stack, options.maxWidth() - pos, breakCache) ? Mode.FLAT : Mode.BREAK;
                }
                stack.push(new Cmd(cmd.indent(), mode, g.child()));
            } else if (d instanceof Doc.IfBreak ib) {
                Doc chosen = cmd.mode() == Mode.BREAK ? ib.whenBroken() : ib.whenFlat();
                stack.push(new Cmd(cmd.indent(), cmd.mode(), chosen));
            } else if (d instanceof Doc.Line) {
                if (cmd.mode() == Mode.FLAT) {
                    out.append(' ');
                    pos += 1;
                } else {
                    if (flushLineSuffixesIfNeeded(stack, lineSuffixes, cmd)) {
                        continue;
                    }
                    pos = newline(out, cmd.indent(), options);
                }
            } else if (d instanceof Doc.SoftLine) {
                if (cmd.mode() == Mode.BREAK) {
                    if (flushLineSuffixesIfNeeded(stack, lineSuffixes, cmd)) {
                        continue;
                    }
                    pos = newline(out, cmd.indent(), options);
                }
            } else if (d instanceof Doc.HardLine) {
                if (flushLineSuffixesIfNeeded(stack, lineSuffixes, cmd)) {
                    continue;
                }
                pos = newline(out, cmd.indent(), options);
            } else if (d instanceof Doc.LineSuffix ls) {
                lineSuffixes.add(new Cmd(cmd.indent(), cmd.mode(), ls.child()));
            } else if (d instanceof Doc.BreakParent) {
                // No text of its own; only affects containsForcedBreak/fits calculations.
            } else if (d instanceof Doc.IndentedVerbatim iv) {
                pos = renderIndentedVerbatim(out, iv, cmd.indent(), options);
            }

            if (stack.isEmpty() && !lineSuffixes.isEmpty()) {
                for (int i = lineSuffixes.size() - 1; i >= 0; i--) {
                    stack.push(lineSuffixes.get(i));
                }
                lineSuffixes.clear();
            }
        }

        // A blank line (two consecutive newlines) would otherwise carry the next line's indent
        // as trailing whitespace, since newline() always emits indentation eagerly rather than
        // deferring it until real content follows.
        return out.toString().replaceAll("(?m)[ \t]+$", "");
    }

    private static boolean flushLineSuffixesIfNeeded(Deque<Cmd> stack, List<Cmd> lineSuffixes, Cmd pendingBreak) {
        if (lineSuffixes.isEmpty()) {
            return false;
        }
        stack.push(pendingBreak);
        for (int i = lineSuffixes.size() - 1; i >= 0; i--) {
            stack.push(lineSuffixes.get(i));
        }
        lineSuffixes.clear();
        return true;
    }

    private static int newline(StringBuilder out, int level, RenderOptions options) {
        out.append('\n');
        out.append(indentText(level, options));
        return level * options.indentWidth();
    }

    private static String indentText(int level, RenderOptions options) {
        return options.indentUnit().repeat(Math.max(0, level));
    }

    /**
     * Emits {@code iv.raw()} with every line after the first re-indented from its original
     * ({@code iv.baseIndent()}) depth to {@code level}, preserving indentation relative to that
     * base. Returns the resulting column position (in {@code indentWidth}-equivalent columns).
     */
    private static int renderIndentedVerbatim(StringBuilder out, Doc.IndentedVerbatim iv, int level, RenderOptions options) {
        String[] lines = iv.raw().split("\n", -1);
        out.append(lines[0]);
        String newIndent = indentText(level, options);
        for (int i = 1; i < lines.length; i++) {
            out.append('\n').append(newIndent).append(dedent(lines[i], iv.baseIndent()));
        }
        String lastLine = dedent(lines[lines.length - 1], iv.baseIndent());
        return lines.length == 1 ? level * options.indentWidth() + lines[0].length()
                : level * options.indentWidth() + lastLine.length();
    }

    /** Strips a leading prefix of {@code line} matching as much of {@code baseIndent} as present. */
    private static String dedent(String line, String baseIndent) {
        int i = 0;
        while (i < line.length() && i < baseIndent.length() && line.charAt(i) == baseIndent.charAt(i)) {
            i++;
        }
        return line.substring(i);
    }

    private static void pushChildrenReversed(Deque<Cmd> stack, int indent, Mode mode, List<Doc> parts) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            stack.push(new Cmd(indent, mode, parts.get(i)));
        }
    }

    /**
     * Whether {@code next}, followed by whatever comes after it in {@code restCommands} up to
     * the next forced/actual newline, fits within {@code width} columns.
     */
    private static boolean fits(Cmd next, Deque<Cmd> restCommands, int width, Map<Doc, Boolean> breakCache) {
        int remaining = width;
        Deque<Cmd> pending = new ArrayDeque<>();
        pending.push(next);
        Iterator<Cmd> restIterator = restCommands.iterator();

        while (remaining >= 0) {
            Cmd cmd;
            if (!pending.isEmpty()) {
                cmd = pending.pop();
            } else if (restIterator.hasNext()) {
                cmd = restIterator.next();
            } else {
                return true;
            }

            Doc d = cmd.doc();
            if (d instanceof Doc.Text t) {
                remaining -= t.value().length();
            } else if (d instanceof Doc.Concat c) {
                pushChildrenReversed(pending, cmd.indent(), cmd.mode(), c.parts());
            } else if (d instanceof Doc.Indent ind) {
                pending.push(new Cmd(cmd.indent(), cmd.mode(), ind.child()));
            } else if (d instanceof Doc.Group g) {
                boolean forced = containsForcedBreak(g.child(), breakCache);
                Mode mode = forced ? Mode.BREAK : cmd.mode();
                pending.push(new Cmd(cmd.indent(), mode, g.child()));
            } else if (d instanceof Doc.IfBreak ib) {
                Doc chosen = cmd.mode() == Mode.BREAK ? ib.whenBroken() : ib.whenFlat();
                pending.push(new Cmd(cmd.indent(), cmd.mode(), chosen));
            } else if (d instanceof Doc.Line) {
                if (cmd.mode() == Mode.FLAT) {
                    remaining -= 1;
                } else {
                    return true;
                }
            } else if (d instanceof Doc.SoftLine) {
                if (cmd.mode() == Mode.BREAK) {
                    return true;
                }
            } else if (d instanceof Doc.HardLine) {
                return true;
            } else if (d instanceof Doc.LineSuffix) {
                // Deferred content never counts toward the current line's width.
            } else if (d instanceof Doc.BreakParent) {
                // No width contribution.
            } else if (d instanceof Doc.IndentedVerbatim iv) {
                // Like HardLine: only the portion before its first embedded newline counts
                // against the current line, and the line necessarily ends there.
                int nl = iv.raw().indexOf('\n');
                remaining -= (nl == -1 ? iv.raw().length() : nl);
                return remaining >= 0;
            }
        }
        return false;
    }

    /** Whether {@code doc} contains a HardLine or BreakParent anywhere, including inside nested groups. */
    private static boolean containsForcedBreak(Doc doc, Map<Doc, Boolean> cache) {
        Boolean cached = cache.get(doc);
        if (cached != null) {
            return cached;
        }
        boolean result;
        if (doc instanceof Doc.HardLine || doc instanceof Doc.BreakParent) {
            result = true;
        } else if (doc instanceof Doc.IndentedVerbatim iv) {
            result = iv.raw().indexOf('\n') != -1;
        } else if (doc instanceof Doc.Concat c) {
            result = c.parts().stream().anyMatch(p -> containsForcedBreak(p, cache));
        } else if (doc instanceof Doc.Group g) {
            result = containsForcedBreak(g.child(), cache);
        } else if (doc instanceof Doc.Indent i) {
            result = containsForcedBreak(i.child(), cache);
        } else {
            // Text, Line, SoftLine, IfBreak, LineSuffix: none of these force an ancestor break.
            result = false;
        }
        cache.put(doc, result);
        return result;
    }
}
