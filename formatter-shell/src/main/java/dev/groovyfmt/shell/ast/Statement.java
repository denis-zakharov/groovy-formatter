package dev.groovyfmt.shell.ast;

/** One top-level {@code and_or}, terminated by {@code ;}, {@code &}, or a newline ({@code ""}). */
public record Statement(AndOr andOr, String terminator) {}
