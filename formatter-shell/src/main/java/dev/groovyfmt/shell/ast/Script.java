package dev.groovyfmt.shell.ast;

/** The whole parsed script: just a top-level {@link StatementList}. */
public record Script(StatementList statements) {}
