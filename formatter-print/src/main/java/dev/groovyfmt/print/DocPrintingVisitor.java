package dev.groovyfmt.print;

import static dev.groovyfmt.doc.Docs.HARDLINE;
import static dev.groovyfmt.doc.Docs.LINE;
import static dev.groovyfmt.doc.Docs.NIL;
import static dev.groovyfmt.doc.Docs.SOFTLINE;
import static dev.groovyfmt.doc.Docs.concat;
import static dev.groovyfmt.doc.Docs.group;
import static dev.groovyfmt.doc.Docs.ifBreak;
import static dev.groovyfmt.doc.Docs.indent;
import static dev.groovyfmt.doc.Docs.join;
import static dev.groovyfmt.doc.Docs.text;

import dev.groovyfmt.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import org.apache.groovy.parser.antlr4.GroovyParser;
import org.apache.groovy.parser.antlr4.GroovyParserBaseVisitor;

/**
 * Walks Groovy's own ANTLR4 parse tree (the CST, not a simplified semantic AST) and emits a
 * {@link Doc} tree. This is Phase 2 of the formatter's build-out: package/imports, one or more
 * top-level classes with fields and methods (no inheritance/generics), variable declarations,
 * assignment, simple/dotted method calls, {@code return}, {@code if}/{@code else}, binary
 * expressions with standard precedence, and integer/string/boolean/null literals.
 *
 * <p>Comments and blank-line preservation land in Phase 3 — this visitor does not look at the
 * token stream at all, only the parse tree's semantic shape, so it discards {@code Nls}/{@code
 * Sep} nodes entirely and re-derives its own canonical spacing. Constructs outside the Phase 2
 * subset (command chains, generics, switch, closures, GStrings, …) throw {@link
 * UnsupportedOperationException} with a description of what's missing, rather than silently
 * dropping content.
 */
final class DocPrintingVisitor extends GroovyParserBaseVisitor<Doc> {

    // ---- Compilation unit --------------------------------------------------------------------

    @Override
    public Doc visitCompilationUnit(GroovyParser.CompilationUnitContext ctx) {
        List<Doc> sections = new ArrayList<>();

        if (ctx.packageDeclaration() != null) {
            sections.add(visit(ctx.packageDeclaration()));
        }

        if (ctx.scriptStatements() != null) {
            List<Doc> imports = new ArrayList<>();
            List<Doc> typeDecls = new ArrayList<>();
            for (GroovyParser.ScriptStatementContext stmt : ctx.scriptStatements().scriptStatement()) {
                if (stmt.importDeclaration() != null) {
                    imports.add(visit(stmt.importDeclaration()));
                } else if (stmt.typeDeclaration() != null) {
                    typeDecls.add(visit(stmt.typeDeclaration()));
                } else {
                    throw unsupported(stmt, "top-level script statements (methods/statements outside a class)");
                }
            }
            if (!imports.isEmpty()) {
                sections.add(join(HARDLINE, imports));
            }
            sections.addAll(typeDecls);
        }

        if (sections.isEmpty()) {
            return NIL;
        }
        return concat(join(concat(HARDLINE, HARDLINE), sections), HARDLINE);
    }

    @Override
    public Doc visitPackageDeclaration(GroovyParser.PackageDeclarationContext ctx) {
        requireNoAnnotations(ctx.annotationsOpt(), ctx);
        return concat(text("package "), printQualifiedName(ctx.qualifiedName()));
    }

    @Override
    public Doc visitImportDeclaration(GroovyParser.ImportDeclarationContext ctx) {
        requireNoAnnotations(ctx.annotationsOpt(), ctx);
        List<Doc> parts = new ArrayList<>();
        parts.add(text("import "));
        if (ctx.STATIC() != null) {
            parts.add(text("static "));
        }
        parts.add(printQualifiedName(ctx.qualifiedName()));
        if (ctx.MUL() != null) {
            parts.add(text(".*"));
        }
        if (ctx.AS() != null) {
            parts.add(text(" as "));
            parts.add(text(ctx.identifier().getText()));
        }
        return concat(parts);
    }

    // ---- Type declarations --------------------------------------------------------------------

    @Override
    public Doc visitTypeDeclaration(GroovyParser.TypeDeclarationContext ctx) {
        List<Doc> parts = new ArrayList<>();
        Doc modifiers = printClassOrInterfaceModifiersOpt(ctx.classOrInterfaceModifiersOpt());
        if (modifiers != null) {
            parts.add(modifiers);
            parts.add(text(" "));
        }
        parts.add(visit(ctx.classDeclaration()));
        return concat(parts);
    }

    @Override
    public Doc visitClassDeclaration(GroovyParser.ClassDeclarationContext ctx) {
        List<Doc> header = new ArrayList<>();
        if (ctx.CLASS() != null) {
            header.add(text("class "));
        } else if (ctx.INTERFACE() != null) {
            header.add(text("interface "));
        } else if (ctx.TRAIT() != null) {
            header.add(text("trait "));
        } else if (ctx.ENUM() != null) {
            header.add(text("enum "));
        } else if (ctx.RECORD() != null) {
            header.add(text("record "));
        } else {
            throw unsupported(ctx, "annotation type declarations");
        }

        if (ctx.EXTENDS() != null
                || ctx.IMPLEMENTS() != null
                || ctx.PERMITS() != null
                || ctx.typeParameters() != null
                || ctx.formalParameters() != null) {
            throw unsupported(ctx, "extends/implements/permits/generics/record headers on class declarations");
        }

        header.add(text(ctx.identifier().getText()));
        header.add(text(" "));
        header.add(visit(ctx.classBody()));
        return concat(header);
    }

    @Override
    public Doc visitClassBody(GroovyParser.ClassBodyContext ctx) {
        if (ctx.enumConstants() != null) {
            throw unsupported(ctx, "enum constants");
        }
        List<? extends GroovyParser.ClassBodyDeclarationContext> decls = ctx.classBodyDeclaration();
        if (decls.isEmpty()) {
            return text("{}");
        }
        List<Doc> members = new ArrayList<>();
        for (GroovyParser.ClassBodyDeclarationContext decl : decls) {
            members.add(visit(decl));
        }
        return concat(
                text("{"),
                indent(concat(HARDLINE, join(concat(HARDLINE, HARDLINE), members))),
                HARDLINE,
                text("}"));
    }

    @Override
    public Doc visitClassBodyDeclaration(GroovyParser.ClassBodyDeclarationContext ctx) {
        if (ctx.block() != null) {
            throw unsupported(ctx, "static/instance initializer blocks");
        }
        return visit(ctx.memberDeclaration());
    }

    @Override
    public Doc visitMemberDeclaration(GroovyParser.MemberDeclarationContext ctx) {
        if (ctx.methodDeclaration() != null) {
            return visit(ctx.methodDeclaration());
        }
        if (ctx.fieldDeclaration() != null) {
            return visit(ctx.fieldDeclaration());
        }
        if (ctx.classDeclaration() != null) {
            List<Doc> parts = new ArrayList<>();
            Doc modifiers = printModifiersOpt(ctx.modifiersOpt());
            if (modifiers != null) {
                parts.add(modifiers);
                parts.add(text(" "));
            }
            parts.add(visit(ctx.classDeclaration()));
            return concat(parts);
        }
        throw unsupported(ctx, "compact constructor declarations (records)");
    }

    // ---- Methods and fields -------------------------------------------------------------------

    @Override
    public Doc visitMethodDeclaration(GroovyParser.MethodDeclarationContext ctx) {
        if (ctx.typeParameters() != null || ctx.THROWS() != null || ctx.DEFAULT() != null) {
            throw unsupported(ctx, "generic methods, throws clauses, and annotation default values");
        }

        List<Doc> parts = new ArrayList<>();
        Doc modifiers = printModifiersOpt(ctx.modifiersOpt());
        if (modifiers != null) {
            parts.add(modifiers);
            parts.add(text(" "));
        }
        if (ctx.returnType() != null) {
            parts.add(printReturnType(ctx.returnType()));
            parts.add(text(" "));
        }
        parts.add(text(printMethodName(ctx.methodName())));
        parts.add(printFormalParameters(ctx.formalParameters()));

        if (ctx.methodBody() == null) {
            parts.add(text(";"));
            return concat(parts);
        }
        parts.add(text(" "));
        parts.add(visit(ctx.methodBody()));
        return concat(parts);
    }

    @Override
    public Doc visitMethodBody(GroovyParser.MethodBodyContext ctx) {
        return visit(ctx.block());
    }

    @Override
    public Doc visitFieldDeclaration(GroovyParser.FieldDeclarationContext ctx) {
        return visit(ctx.variableDeclaration());
    }

    @Override
    public Doc visitLocalVariableDeclaration(GroovyParser.LocalVariableDeclarationContext ctx) {
        return visit(ctx.variableDeclaration());
    }

    @Override
    public Doc visitVariableDeclaration(GroovyParser.VariableDeclarationContext ctx) {
        if (ctx.typeNamePairs() != null) {
            throw unsupported(ctx, "multiple-assignment / tuple declarations");
        }
        List<Doc> parts = new ArrayList<>();
        if (ctx.modifiers() != null) {
            parts.add(printModifiers(ctx.modifiers()));
            parts.add(text(" "));
        }
        if (ctx.type() != null) {
            parts.add(printType(ctx.type()));
            parts.add(text(" "));
        } else if (ctx.modifiers() == null) {
            throw unsupported(ctx, "variable declarations without a type or modifiers");
        }
        parts.add(visit(ctx.variableDeclarators()));
        return concat(parts);
    }

    @Override
    public Doc visitVariableDeclarators(GroovyParser.VariableDeclaratorsContext ctx) {
        List<Doc> decls = new ArrayList<>();
        for (GroovyParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            decls.add(visit(d));
        }
        return join(text(", "), decls);
    }

    @Override
    public Doc visitVariableDeclarator(GroovyParser.VariableDeclaratorContext ctx) {
        List<Doc> parts = new ArrayList<>();
        parts.add(text(ctx.variableDeclaratorId().identifier().getText()));
        if (ctx.ASSIGN() != null) {
            parts.add(text(" = "));
            parts.add(visit(ctx.variableInitializer()));
        }
        return concat(parts);
    }

    @Override
    public Doc visitVariableInitializer(GroovyParser.VariableInitializerContext ctx) {
        return visit(ctx.enhancedStatementExpression());
    }

    private String printMethodName(GroovyParser.MethodNameContext ctx) {
        if (ctx.identifier() != null) {
            return ctx.identifier().getText();
        }
        return ctx.stringLiteral().getText();
    }

    private Doc printReturnType(GroovyParser.ReturnTypeContext ctx) {
        if (ctx.VOID() != null) {
            return text("void");
        }
        return printType(ctx.type());
    }

    private Doc printFormalParameters(GroovyParser.FormalParametersContext ctx) {
        if (ctx.formalParameterList() == null) {
            return text("()");
        }
        GroovyParser.FormalParameterListContext list = ctx.formalParameterList();
        if (list.thisFormalParameter() != null) {
            throw unsupported(ctx, "explicit 'this' formal parameters (trait/inner-class syntax)");
        }
        List<Doc> paramDocs = new ArrayList<>();
        for (GroovyParser.FormalParameterContext p : list.formalParameter()) {
            paramDocs.add(printFormalParameter(p));
        }
        return group(
                concat(
                        text("("),
                        indent(concat(SOFTLINE, join(concat(text(","), LINE), paramDocs))),
                        ifBreak(text(","), NIL),
                        SOFTLINE,
                        text(")")));
    }

    private Doc printFormalParameter(GroovyParser.FormalParameterContext ctx) {
        if (ctx.ELLIPSIS() != null) {
            throw unsupported(ctx, "varargs parameters");
        }
        if (ctx.ASSIGN() != null) {
            throw unsupported(ctx, "parameter default values");
        }
        List<Doc> parts = new ArrayList<>();
        if (ctx.variableModifiersOpt() != null && ctx.variableModifiersOpt().variableModifiers() != null) {
            parts.add(printVariableModifiers(ctx.variableModifiersOpt().variableModifiers()));
            parts.add(text(" "));
        }
        if (ctx.type() != null) {
            parts.add(printType(ctx.type()));
            parts.add(text(" "));
        } else {
            parts.add(text("def "));
        }
        parts.add(text(ctx.variableDeclaratorId().identifier().getText()));
        return concat(parts);
    }

    // ---- Modifiers and types -------------------------------------------------------------------

    private Doc printClassOrInterfaceModifiersOpt(GroovyParser.ClassOrInterfaceModifiersOptContext ctx) {
        if (ctx == null || ctx.classOrInterfaceModifiers() == null) {
            return null;
        }
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.ClassOrInterfaceModifierContext m : ctx.classOrInterfaceModifiers().classOrInterfaceModifier()) {
            parts.add(printClassOrInterfaceModifier(m));
        }
        return join(text(" "), parts);
    }

    private Doc printClassOrInterfaceModifier(GroovyParser.ClassOrInterfaceModifierContext ctx) {
        if (ctx.annotation() != null) {
            throw unsupported(ctx, "annotations");
        }
        return text(ctx.getText());
    }

    private Doc printModifiersOpt(GroovyParser.ModifiersOptContext ctx) {
        if (ctx == null || ctx.modifiers() == null) {
            return null;
        }
        return printModifiers(ctx.modifiers());
    }

    private Doc printModifiers(GroovyParser.ModifiersContext ctx) {
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.ModifierContext m : ctx.modifier()) {
            parts.add(printModifier(m));
        }
        return join(text(" "), parts);
    }

    private Doc printModifier(GroovyParser.ModifierContext ctx) {
        if (ctx.classOrInterfaceModifier() != null) {
            return printClassOrInterfaceModifier(ctx.classOrInterfaceModifier());
        }
        return text(ctx.getText());
    }

    private Doc printVariableModifiers(GroovyParser.VariableModifiersContext ctx) {
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.VariableModifierContext m : ctx.variableModifier()) {
            if (m.annotation() != null) {
                throw unsupported(m, "annotations");
            }
            parts.add(text(m.getText()));
        }
        return join(text(" "), parts);
    }

    private Doc printType(GroovyParser.TypeContext ctx) {
        requireNoAnnotations(ctx.annotationsOpt(), ctx);
        if (ctx.VOID() != null) {
            return text("void");
        }
        StringBuilder sb = new StringBuilder();
        if (ctx.primitiveType() != null) {
            sb.append(ctx.primitiveType().getText());
        } else {
            GroovyParser.ClassOrInterfaceTypeContext coi = ctx.classOrInterfaceType();
            if (coi.typeArguments() != null) {
                throw unsupported(coi, "generic type arguments");
            }
            sb.append(coi.qualifiedClassName() != null
                    ? coi.qualifiedClassName().getText()
                    : coi.qualifiedStandardClassName().getText());
        }
        if (ctx.emptyDimsOpt().emptyDims() != null) {
            sb.append(ctx.emptyDimsOpt().emptyDims().getText());
        }
        return text(sb.toString());
    }

    private Doc printQualifiedName(GroovyParser.QualifiedNameContext ctx) {
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.QualifiedNameElementContext element : ctx.qualifiedNameElement()) {
            parts.add(text(element.getText()));
        }
        return join(text("."), parts);
    }

    // ---- Blocks and statements -----------------------------------------------------------------

    @Override
    public Doc visitBlock(GroovyParser.BlockContext ctx) {
        List<Doc> statements = printBlockStatements(ctx.blockStatementsOpt());
        if (statements.isEmpty()) {
            return text("{}");
        }
        return concat(text("{"), indent(concat(HARDLINE, join(HARDLINE, statements))), HARDLINE, text("}"));
    }

    private List<Doc> printBlockStatements(GroovyParser.BlockStatementsOptContext ctx) {
        List<Doc> result = new ArrayList<>();
        if (ctx == null || ctx.blockStatements() == null) {
            return result;
        }
        for (GroovyParser.BlockStatementContext stmt : ctx.blockStatements().blockStatement()) {
            result.add(visit(stmt));
        }
        return result;
    }

    @Override
    public Doc visitBlockStatement(GroovyParser.BlockStatementContext ctx) {
        if (ctx.localVariableDeclaration() != null) {
            return visit(ctx.localVariableDeclaration());
        }
        return visit(ctx.statement());
    }

    @Override
    public Doc visitExpressionStmtAlt(GroovyParser.ExpressionStmtAltContext ctx) {
        return visit(ctx.statementExpression());
    }

    @Override
    public Doc visitLocalVariableDeclarationStmtAlt(GroovyParser.LocalVariableDeclarationStmtAltContext ctx) {
        return visit(ctx.localVariableDeclaration());
    }

    @Override
    public Doc visitReturnStmtAlt(GroovyParser.ReturnStmtAltContext ctx) {
        if (ctx.expression() == null) {
            return text("return");
        }
        return concat(text("return "), visit(ctx.expression()));
    }

    @Override
    public Doc visitBlockStmtAlt(GroovyParser.BlockStmtAltContext ctx) {
        return visit(ctx.block());
    }

    @Override
    public Doc visitEmptyStmtAlt(GroovyParser.EmptyStmtAltContext ctx) {
        return NIL;
    }

    @Override
    public Doc visitConditionalStmtAlt(GroovyParser.ConditionalStmtAltContext ctx) {
        return visit(ctx.conditionalStatement());
    }

    @Override
    public Doc visitConditionalStatement(GroovyParser.ConditionalStatementContext ctx) {
        if (ctx.switchStatement() != null) {
            throw unsupported(ctx, "switch statements");
        }
        return visit(ctx.ifElseStatement());
    }

    @Override
    public Doc visitIfElseStatement(GroovyParser.IfElseStatementContext ctx) {
        List<Doc> parts = new ArrayList<>();
        parts.add(text("if "));
        parts.add(visit(ctx.expressionInPar()));
        parts.add(text(" "));
        parts.add(visit(ctx.tb));
        if (ctx.ELSE() != null) {
            parts.add(text(" else "));
            parts.add(visit(ctx.fb));
        }
        return concat(parts);
    }

    @Override
    public Doc visitExpressionInPar(GroovyParser.ExpressionInParContext ctx) {
        return concat(text("("), visit(ctx.enhancedStatementExpression()), text(")"));
    }

    // ---- Expressions -----------------------------------------------------------------------

    @Override
    public Doc visitEnhancedStatementExpression(GroovyParser.EnhancedStatementExpressionContext ctx) {
        if (ctx.standardLambdaExpression() != null) {
            throw unsupported(ctx, "lambda expressions");
        }
        return visit(ctx.statementExpression());
    }

    @Override
    public Doc visitCommandExprAlt(GroovyParser.CommandExprAltContext ctx) {
        return visit(ctx.commandExpression());
    }

    @Override
    public Doc visitCommandExpression(GroovyParser.CommandExpressionContext ctx) {
        // Command-chain syntax has two shapes: multi-word chains ('foo bar baz', via
        // commandArgument()) and paren-less named-argument shorthand ('foo bar: 1', which
        // attaches an enhancedArgumentListInPar() directly to this node). Both are out of scope
        // until Phase 4 — must check both, or the shorthand's arguments get silently dropped.
        if (ctx.enhancedArgumentListInPar() != null || !ctx.commandArgument().isEmpty()) {
            throw unsupported(ctx, "command-chain expressions (e.g. 'foo bar: 1, baz: 2')");
        }
        return visit(ctx.expression());
    }

    @Override
    public Doc visitAssignmentExprAlt(GroovyParser.AssignmentExprAltContext ctx) {
        Doc left = visit(ctx.left);
        Doc right = ctx.enhancedStatementExpression() != null
                ? visit(ctx.enhancedStatementExpression())
                : visit(ctx.expression());
        return binaryDoc(left, ctx.op.getText(), right);
    }

    @Override
    public Doc visitAdditiveExprAlt(GroovyParser.AdditiveExprAltContext ctx) {
        return binaryDoc(visit(ctx.left), ctx.op.getText(), visit(ctx.right));
    }

    @Override
    public Doc visitMultiplicativeExprAlt(GroovyParser.MultiplicativeExprAltContext ctx) {
        return binaryDoc(visit(ctx.left), ctx.op.getText(), visit(ctx.right));
    }

    @Override
    public Doc visitRelationalExprAlt(GroovyParser.RelationalExprAltContext ctx) {
        if (ctx.type() != null) {
            throw unsupported(ctx, "'instanceof'/'as' type expressions");
        }
        return binaryDoc(visit(ctx.left), ctx.op.getText(), visit(ctx.right));
    }

    @Override
    public Doc visitEqualityExprAlt(GroovyParser.EqualityExprAltContext ctx) {
        return binaryDoc(visit(ctx.left), ctx.op.getText(), visit(ctx.right));
    }

    @Override
    public Doc visitLogicalAndExprAlt(GroovyParser.LogicalAndExprAltContext ctx) {
        return binaryDoc(visit(ctx.left), ctx.op.getText(), visit(ctx.right));
    }

    @Override
    public Doc visitLogicalOrExprAlt(GroovyParser.LogicalOrExprAltContext ctx) {
        return binaryDoc(visit(ctx.left), ctx.op.getText(), visit(ctx.right));
    }

    @Override
    public Doc visitUnaryAddExprAlt(GroovyParser.UnaryAddExprAltContext ctx) {
        if (ctx.INC() != null || ctx.DEC() != null) {
            throw unsupported(ctx, "prefix ++/--");
        }
        return concat(text(ctx.op.getText()), visit(ctx.expression()));
    }

    @Override
    public Doc visitUnaryNotExprAlt(GroovyParser.UnaryNotExprAltContext ctx) {
        String op = ctx.NOT() != null ? "!" : "~";
        return concat(text(op), visit(ctx.expression()));
    }

    @Override
    public Doc visitPostfixExprAlt(GroovyParser.PostfixExprAltContext ctx) {
        return visit(ctx.postfixExpression());
    }

    @Override
    public Doc visitPostfixExpression(GroovyParser.PostfixExpressionContext ctx) {
        Doc base = visit(ctx.pathExpression());
        if (ctx.op != null) {
            return concat(base, text(ctx.op.getText()));
        }
        return base;
    }

    @Override
    public Doc visitPathExpression(GroovyParser.PathExpressionContext ctx) {
        if (ctx.STATIC() != null) {
            throw unsupported(ctx, "'static' path-expression prefix");
        }
        List<Doc> parts = new ArrayList<>();
        parts.add(visit(ctx.primary()));
        for (GroovyParser.PathElementContext element : ctx.pathElement()) {
            parts.add(printPathElement(element));
        }
        return concat(parts);
    }

    private Doc printPathElement(GroovyParser.PathElementContext ctx) {
        if (ctx.arguments() != null) {
            return printArguments(ctx.arguments());
        }
        if (ctx.namePart() != null) {
            String connector;
            if (ctx.SAFE_CHAIN_DOT() != null) {
                connector = "?..";
            } else if (ctx.SAFE_DOT() != null) {
                connector = "?.";
            } else if (ctx.SPREAD_DOT() != null) {
                connector = "*.";
            } else if (ctx.DOT() != null) {
                connector = ".";
            } else {
                throw unsupported(ctx, "method pointer/reference path elements");
            }
            return concat(text(connector), text(printNamePart(ctx.namePart())));
        }
        throw unsupported(ctx, "index/named-property/closure/new path elements");
    }

    private String printNamePart(GroovyParser.NamePartContext ctx) {
        if (ctx.identifier() != null) {
            return ctx.identifier().getText();
        }
        if (ctx.stringLiteral() != null) {
            return ctx.stringLiteral().getText();
        }
        if (ctx.keywords() != null) {
            return ctx.keywords().getText();
        }
        throw unsupported(ctx, "dynamic member name path elements");
    }

    private Doc printArguments(GroovyParser.ArgumentsContext ctx) {
        if (ctx.enhancedArgumentListInPar() == null) {
            return text("()");
        }
        List<Doc> args = new ArrayList<>();
        for (GroovyParser.EnhancedArgumentListElementContext e : ctx.enhancedArgumentListInPar().enhancedArgumentListElement()) {
            args.add(printArgumentElement(e));
        }
        return group(
                concat(
                        text("("),
                        indent(concat(SOFTLINE, join(concat(text(","), LINE), args))),
                        ifBreak(text(","), NIL),
                        SOFTLINE,
                        text(")")));
    }

    private Doc printArgumentElement(GroovyParser.EnhancedArgumentListElementContext ctx) {
        if (ctx.expressionListElement() != null) {
            GroovyParser.ExpressionListElementContext e = ctx.expressionListElement();
            if (e.MUL() != null) {
                throw unsupported(e, "spread arguments (*args)");
            }
            return visit(e.expression());
        }
        if (ctx.mapEntry() != null) {
            throw unsupported(ctx, "named/map arguments");
        }
        throw unsupported(ctx, "lambda arguments");
    }

    @Override
    public Doc visitIdentifierPrmrAlt(GroovyParser.IdentifierPrmrAltContext ctx) {
        if (ctx.typeArguments() != null) {
            throw unsupported(ctx, "explicit generic type arguments on identifiers");
        }
        return text(ctx.identifier().getText());
    }

    @Override
    public Doc visitLiteralPrmrAlt(GroovyParser.LiteralPrmrAltContext ctx) {
        return visit(ctx.literal());
    }

    @Override
    public Doc visitParenPrmrAlt(GroovyParser.ParenPrmrAltContext ctx) {
        return visit(ctx.parExpression());
    }

    @Override
    public Doc visitParExpression(GroovyParser.ParExpressionContext ctx) {
        return visit(ctx.expressionInPar());
    }

    @Override
    public Doc visitIntegerLiteralAlt(GroovyParser.IntegerLiteralAltContext ctx) {
        return text(ctx.getText());
    }

    @Override
    public Doc visitFloatingPointLiteralAlt(GroovyParser.FloatingPointLiteralAltContext ctx) {
        return text(ctx.getText());
    }

    @Override
    public Doc visitStringLiteralAlt(GroovyParser.StringLiteralAltContext ctx) {
        return text(ctx.stringLiteral().getText());
    }

    @Override
    public Doc visitBooleanLiteralAlt(GroovyParser.BooleanLiteralAltContext ctx) {
        return text(ctx.getText());
    }

    @Override
    public Doc visitNullLiteralAlt(GroovyParser.NullLiteralAltContext ctx) {
        return text("null");
    }

    private Doc binaryDoc(Doc left, String op, Doc right) {
        return group(concat(left, text(" " + op), indent(concat(LINE, right))));
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private void requireNoAnnotations(GroovyParser.AnnotationsOptContext ctx, GroovyParser.GroovyParserRuleContext owner) {
        if (ctx != null && !ctx.annotation().isEmpty()) {
            throw unsupported(owner, "annotations");
        }
    }

    private static UnsupportedOperationException unsupported(GroovyParser.GroovyParserRuleContext ctx, String what) {
        return new UnsupportedOperationException(
                "groovy-formatter: " + what + " not supported yet (Phase 2 subset): '" + ctx.getText() + "'");
    }
}
