package dev.groovyfmt.shell.ast;

/**
 * Mutable holder for a here-doc's body text. The parser creates the {@link Redirection.HereDoc}
 * AST node before the body text exists — the lexer only reads it once it crosses the newline
 * ending the redirect's opening line, which happens later, while the parser keeps consuming the
 * rest of the same statement. {@link #text} is filled in by then, always, before printing (the
 * whole script is parsed before any printing starts).
 */
public final class HereDocBody {
    public String text;
}
