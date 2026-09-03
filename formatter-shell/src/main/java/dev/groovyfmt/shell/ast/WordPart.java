package dev.groovyfmt.shell.ast;

import java.util.List;

/**
 * One piece of a {@link Word}. A word is split into parts only at quote and expansion
 * boundaries — the raw text *inside* each part (an unquoted run, a quoted body, an expansion's
 * inner text) is kept verbatim and never reinterpreted or reformatted, the same philosophy
 * {@code formatter-print} uses for GString bodies: this is a structural formatter, not a content
 * normalizer.
 */
public sealed interface WordPart {

    /** An unquoted run of characters, including any backslash-escapes, exactly as written. */
    record Literal(String text) implements WordPart {}

    /** {@code 'raw'} — {@code raw} is the exact text between the quotes. */
    record SingleQuoted(String raw) implements WordPart {}

    /** {@code $'raw'} (ANSI-C quoting) — {@code raw} is the exact text between the quotes. */
    record AnsiCQuoted(String raw) implements WordPart {}

    /** {@code "..."} — {@code parts} are the nested Literal/expansion parts inside the quotes. */
    record DoubleQuoted(List<WordPart> parts) implements WordPart {}

    /** {@code $"..."} (bash locale-translated string) — same shape as {@link DoubleQuoted}. */
    record LocaleQuoted(List<WordPart> parts) implements WordPart {}

    /**
     * {@code $name}, {@code $1}, {@code $?}, or {@code ${...}} — {@code raw} is everything after
     * the {@code $} verbatim (including the braces, for the brace form). Never reformatted: the
     * operators inside {@code ${var:-default}} etc. are out of scope.
     */
    record ParamExpansion(String raw) implements WordPart {}

    /**
     * {@code $(...)} or {@code `...`} — {@code raw} is the exact inner text (balanced, quotes
     * respected). For the {@code $(...)} form the printer recursively parses and reformats
     * {@code raw} as a nested script; a backtick substitution is always printed verbatim, since
     * its escaping rules differ and re-lexing it is not worth the added complexity.
     */
    record CommandSubstitution(String raw, boolean backtick) implements WordPart {}

    /** {@code $((...))} — {@code raw} is the exact inner text. Never reformatted (out of scope). */
    record ArithExpansion(String raw) implements WordPart {}
}
