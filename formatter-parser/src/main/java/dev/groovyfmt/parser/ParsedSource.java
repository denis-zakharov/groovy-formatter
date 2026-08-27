package dev.groovyfmt.parser;

import groovyjarjarantlr4.v4.runtime.CommonTokenStream;
import org.apache.groovy.parser.antlr4.GroovyParser;

/**
 * The result of parsing a Groovy source file: the ANTLR4 parse tree (concrete syntax tree, not
 * just the semantic AST) plus the full token stream, including hidden-channel tokens. Downstream
 * comment/blank-line handling needs the raw token stream, not just the tree.
 */
public record ParsedSource(GroovyParser.CompilationUnitContext compilationUnit, CommonTokenStream tokens) {}
