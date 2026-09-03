package dev.groovyfmt.shell.ast;

/** A {@code #...} comment, {@code text} being the full comment including the {@code #}. */
public record Comment(String text) {}
