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
import static dev.groovyfmt.doc.Docs.lineSuffix;
import static dev.groovyfmt.doc.Docs.text;

import dev.groovyfmt.comments.CommentAttacher;
import dev.groovyfmt.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import org.apache.groovy.parser.antlr4.GroovyParser;
import org.apache.groovy.parser.antlr4.GroovyParserBaseVisitor;

/**
 * Walks Groovy's own ANTLR4 parse tree (the CST, not a simplified semantic AST) and emits a
 * {@link Doc} tree. Phase 2 covers: package/imports, one or more top-level classes with fields
 * and methods (no inheritance/generics), variable declarations, assignment, simple/dotted method
 * calls, {@code return}, {@code if}/{@code else}, binary expressions with standard precedence,
 * and integer/string/boolean/null literals. Phase 3 adds comment and blank-line preservation
 * between statements/members (via {@link CommentAttacher}) — comments nested inside a single
 * statement/expression are still out of scope and throw rather than being misplaced.
 *
 * <p>Outside of comment/blank-line handling, this visitor otherwise ignores the token stream —
 * it discards {@code Nls}/{@code Sep} nodes entirely and re-derives its own canonical spacing.
 * Constructs outside the Phase 2 subset (command chains, generics, switch, closures, GStrings, …)
 * throw {@link UnsupportedOperationException} with a description of what's missing, rather than
 * silently dropping content.
 */
final class DocPrintingVisitor extends GroovyParserBaseVisitor<Doc> {

    private final CommentAttacher commentAttacher;
    private final String source;

    DocPrintingVisitor(CommentAttacher commentAttacher, String source) {
        this.commentAttacher = commentAttacher;
        this.source = source;
    }

    // ---- Compilation unit --------------------------------------------------------------------

    @Override
    public Doc visitCompilationUnit(GroovyParser.CompilationUnitContext ctx) {
        List<GroovyParser.GroovyParserRuleContext> siblings = new ArrayList<>();
        if (ctx.packageDeclaration() != null) {
            siblings.add(ctx.packageDeclaration());
        }
        if (ctx.scriptStatements() != null) {
            for (GroovyParser.ScriptStatementContext stmt : ctx.scriptStatements().scriptStatement()) {
                if (stmt.importDeclaration() != null) {
                    siblings.add(stmt.importDeclaration());
                } else if (stmt.typeDeclaration() != null) {
                    siblings.add(stmt.typeDeclaration());
                } else if (stmt.methodDeclaration() != null) {
                    siblings.add(stmt.methodDeclaration());
                } else {
                    siblings.add(stmt.statement());
                }
            }
        }

        int stopTokenIndex = ctx.EOF().getSymbol().getTokenIndex();
        List<CommentAttacher.Item> items = commentAttacher.attach(siblings, -1, stopTokenIndex);
        if (items.isEmpty()) {
            return NIL;
        }
        return concat(printAttachedItems(items), HARDLINE);
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

        if (ctx.ps != null) {
            throw unsupported(ctx, "sealed class 'permits' clauses");
        }

        header.add(text(ctx.identifier().getText()));
        if (ctx.typeParameters() != null) {
            header.add(printTypeParameters(ctx.typeParameters()));
        }
        if (ctx.formalParameters() != null) {
            header.add(printFormalParameters(ctx.formalParameters()));
        }
        if (ctx.scs != null) {
            header.add(text(" extends "));
            header.add(printTypeList(ctx.scs));
        }
        if (ctx.is != null) {
            header.add(text(" implements "));
            header.add(printTypeList(ctx.is));
        }
        header.add(text(" "));
        header.add(visit(ctx.classBody()));
        return concat(header);
    }

    private Doc printTypeParameters(GroovyParser.TypeParametersContext ctx) {
        List<Doc> params = new ArrayList<>();
        for (GroovyParser.TypeParameterContext p : ctx.typeParameter()) {
            params.add(printTypeParameter(p));
        }
        return concat(text("<"), join(text(", "), params), text(">"));
    }

    private Doc printTypeParameter(GroovyParser.TypeParameterContext ctx) {
        requireNoAnnotations(ctx.annotationsOpt(), ctx);
        Doc name = text(ctx.className().getText());
        if (ctx.typeBound() == null) {
            return name;
        }
        List<Doc> bounds = new ArrayList<>();
        for (GroovyParser.TypeContext t : ctx.typeBound().type()) {
            bounds.add(printType(t));
        }
        return concat(name, text(" extends "), join(text(" & "), bounds));
    }

    private Doc printTypeList(GroovyParser.TypeListContext ctx) {
        List<Doc> types = new ArrayList<>();
        for (GroovyParser.TypeContext t : ctx.type()) {
            types.add(printType(t));
        }
        return join(text(", "), types);
    }

    @Override
    public Doc visitClassBody(GroovyParser.ClassBodyContext ctx) {
        List<CommentAttacher.Item> items = commentAttacher.attach(
                ctx.classBodyDeclaration(),
                ctx.LBRACE().getSymbol().getTokenIndex(),
                ctx.RBRACE().getSymbol().getTokenIndex());
        Doc enumConstants = ctx.enumConstants() == null ? null : printEnumConstants(ctx.enumConstants());

        if (enumConstants == null && items.isEmpty()) {
            return text("{}");
        }
        List<Doc> bodyParts = new ArrayList<>();
        if (enumConstants != null) {
            bodyParts.add(enumConstants);
        }
        if (!items.isEmpty()) {
            if (enumConstants != null) {
                bodyParts.add(concat(HARDLINE, HARDLINE));
            }
            bodyParts.add(printAttachedItems(items));
        }
        return concat(text("{"), indent(concat(HARDLINE, concat(bodyParts))), HARDLINE, text("}"));
    }

    private Doc printEnumConstants(GroovyParser.EnumConstantsContext ctx) {
        List<Doc> constants = new ArrayList<>();
        for (GroovyParser.EnumConstantContext c : ctx.enumConstant()) {
            constants.add(printEnumConstant(c));
        }
        return group(join(concat(text(","), LINE), constants));
    }

    private Doc printEnumConstant(GroovyParser.EnumConstantContext ctx) {
        if (ctx.anonymousInnerClassDeclaration() != null) {
            throw unsupported(ctx, "enum constants with class bodies");
        }
        requireNoAnnotations(ctx.annotationsOpt(), ctx);
        Doc name = text(ctx.identifier().getText());
        return ctx.arguments() == null ? name : concat(name, printArguments(ctx.arguments()));
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
            }
            parts.add(visit(ctx.classDeclaration()));
            return concat(parts);
        }
        throw unsupported(ctx, "compact constructor declarations (records)");
    }

    // ---- Methods and fields -------------------------------------------------------------------

    @Override
    public Doc visitMethodDeclaration(GroovyParser.MethodDeclarationContext ctx) {
        if (ctx.DEFAULT() != null) {
            throw unsupported(ctx, "annotation default values");
        }

        List<Doc> parts = new ArrayList<>();
        Doc modifiers = printModifiersOpt(ctx.modifiersOpt());
        if (modifiers != null) {
            parts.add(modifiers);
        }
        if (ctx.typeParameters() != null) {
            parts.add(printTypeParameters(ctx.typeParameters()));
            parts.add(text(" "));
        }
        if (ctx.returnType() != null) {
            parts.add(printReturnType(ctx.returnType()));
            parts.add(text(" "));
        }
        parts.add(text(printMethodName(ctx.methodName())));
        parts.add(printFormalParameters(ctx.formalParameters()));
        if (ctx.THROWS() != null) {
            parts.add(text(" throws "));
            parts.add(printQualifiedClassNameList(ctx.qualifiedClassNameList()));
        }

        if (ctx.methodBody() == null) {
            parts.add(text(";"));
            return concat(parts);
        }
        parts.add(text(" "));
        parts.add(visit(ctx.methodBody()));
        return concat(parts);
    }

    private Doc printQualifiedClassNameList(GroovyParser.QualifiedClassNameListContext ctx) {
        List<Doc> types = new ArrayList<>();
        for (GroovyParser.AnnotatedQualifiedClassNameContext t : ctx.annotatedQualifiedClassName()) {
            requireNoAnnotations(t.annotationsOpt(), t);
            types.add(text(t.qualifiedClassName().getText()));
        }
        return join(text(", "), types);
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
        }
        if (ctx.ELLIPSIS() != null) {
            parts.add(text("..."));
        }
        if (ctx.type() != null || ctx.ELLIPSIS() != null) {
            parts.add(text(" "));
        }
        // An untyped, non-varargs parameter with no modifiers (e.g. closure params
        // `{ a, b -> ... }`, or a method param declared without a type) is written bare — Groovy
        // formal parameters don't need (and don't accept) 'def' as a type placeholder the way
        // local variable/field declarations do.
        parts.add(text(ctx.variableDeclaratorId().identifier().getText()));
        return concat(parts);
    }

    // ---- Modifiers and types -------------------------------------------------------------------

    private Doc printClassOrInterfaceModifiersOpt(GroovyParser.ClassOrInterfaceModifiersOptContext ctx) {
        if (ctx == null || ctx.classOrInterfaceModifiers() == null) {
            return null;
        }
        // Includes its own trailing separator after every modifier (including the last) — an
        // annotation always goes on its own line (the conventional style for @Override,
        // @Deprecated, ...), a plain keyword modifier (public/static/...) just gets a space.
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.ClassOrInterfaceModifierContext m : ctx.classOrInterfaceModifiers().classOrInterfaceModifier()) {
            parts.add(printClassOrInterfaceModifier(m));
            parts.add(m.annotation() != null ? HARDLINE : text(" "));
        }
        return concat(parts);
    }

    private Doc printClassOrInterfaceModifier(GroovyParser.ClassOrInterfaceModifierContext ctx) {
        if (ctx.annotation() != null) {
            return printAnnotation(ctx.annotation());
        }
        return text(ctx.getText());
    }

    private Doc printAnnotation(GroovyParser.AnnotationContext ctx) {
        Doc name = concat(text("@"), text(ctx.annotationName().getText()));
        return ctx.elementValues() == null ? name : concat(name, printElementValues(ctx.elementValues()));
    }

    private Doc printElementValues(GroovyParser.ElementValuesContext ctx) {
        if (ctx.elementValuePairs() != null) {
            List<Doc> pairs = new ArrayList<>();
            for (GroovyParser.ElementValuePairContext p : ctx.elementValuePairs().elementValuePair()) {
                pairs.add(printElementValuePair(p));
            }
            return concat(text("("), join(text(", "), pairs), text(")"));
        }
        return concat(text("("), printElementValue(ctx.elementValue()), text(")"));
    }

    private Doc printElementValuePair(GroovyParser.ElementValuePairContext ctx) {
        GroovyParser.ElementValuePairNameContext nameCtx = ctx.elementValuePairName();
        String name = nameCtx.identifier() != null ? nameCtx.identifier().getText() : nameCtx.keywords().getText();
        return concat(text(name), text(" = "), printElementValue(ctx.elementValue()));
    }

    private Doc printElementValue(GroovyParser.ElementValueContext ctx) {
        if (ctx.expression() != null) {
            return visit(ctx.expression());
        }
        if (ctx.annotation() != null) {
            return printAnnotation(ctx.annotation());
        }
        List<Doc> values = new ArrayList<>();
        for (GroovyParser.ElementValueContext v : ctx.elementValueArrayInitializer().elementValue()) {
            values.add(printElementValue(v));
        }
        return concat(text("["), join(text(", "), values), text("]"));
    }

    private Doc printModifiersOpt(GroovyParser.ModifiersOptContext ctx) {
        if (ctx == null || ctx.modifiers() == null) {
            return null;
        }
        return printModifiers(ctx.modifiers());
    }

    private Doc printModifiers(GroovyParser.ModifiersContext ctx) {
        // Includes its own trailing separator after every modifier (including the last) — see
        // printClassOrInterfaceModifiersOpt for why.
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.ModifierContext m : ctx.modifier()) {
            boolean isAnnotation = m.classOrInterfaceModifier() != null && m.classOrInterfaceModifier().annotation() != null;
            parts.add(printModifier(m));
            parts.add(isAnnotation ? HARDLINE : text(" "));
        }
        return concat(parts);
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
            parts.add(m.annotation() != null ? printAnnotation(m.annotation()) : text(m.getText()));
        }
        return join(text(" "), parts);
    }

    private Doc printType(GroovyParser.TypeContext ctx) {
        Doc annotations = printAnnotationsOptInline(ctx.annotationsOpt());
        if (ctx.VOID() != null) {
            return concat(annotations, text("void"));
        }
        Doc base = ctx.primitiveType() != null
                ? text(ctx.primitiveType().getText())
                : printClassOrInterfaceType(ctx.classOrInterfaceType());
        Doc dims = ctx.emptyDimsOpt().emptyDims() != null ? text(ctx.emptyDimsOpt().emptyDims().getText()) : NIL;
        return concat(annotations, base, dims);
    }

    private Doc printClassOrInterfaceType(GroovyParser.ClassOrInterfaceTypeContext ctx) {
        String name = ctx.qualifiedClassName() != null
                ? ctx.qualifiedClassName().getText()
                : ctx.qualifiedStandardClassName().getText();
        return ctx.typeArguments() == null ? text(name) : concat(text(name), printTypeArguments(ctx.typeArguments()));
    }

    private Doc printTypeArguments(GroovyParser.TypeArgumentsContext ctx) {
        List<Doc> args = new ArrayList<>();
        for (GroovyParser.TypeArgumentContext a : ctx.typeArgument()) {
            args.add(printTypeArgument(a));
        }
        return concat(text("<"), join(text(", "), args), text(">"));
    }

    private Doc printTypeArgument(GroovyParser.TypeArgumentContext ctx) {
        requireNoAnnotations(ctx.annotationsOpt(), ctx);
        if (ctx.QUESTION() == null) {
            return printType(ctx.type());
        }
        if (ctx.EXTENDS() != null) {
            return concat(text("? extends "), printType(ctx.type()));
        }
        if (ctx.SUPER() != null) {
            return concat(text("? super "), printType(ctx.type()));
        }
        return text("?");
    }

    private Doc printAnnotationsOptInline(GroovyParser.AnnotationsOptContext ctx) {
        if (ctx == null || ctx.annotation().isEmpty()) {
            return NIL;
        }
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.AnnotationContext a : ctx.annotation()) {
            parts.add(printAnnotation(a));
            parts.add(text(" "));
        }
        return concat(parts);
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
        List<? extends GroovyParser.BlockStatementContext> statements =
                ctx.blockStatementsOpt().blockStatements() == null
                        ? List.of()
                        : ctx.blockStatementsOpt().blockStatements().blockStatement();
        List<CommentAttacher.Item> items = commentAttacher.attach(
                statements, ctx.LBRACE().getSymbol().getTokenIndex(), ctx.RBRACE().getSymbol().getTokenIndex());
        if (items.isEmpty()) {
            return text("{}");
        }
        return concat(text("{"), indent(concat(HARDLINE, printAttachedItems(items))), HARDLINE, text("}"));
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
            return visit(ctx.switchStatement());
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

    @Override
    public Doc visitSwitchStatement(GroovyParser.SwitchStatementContext ctx) {
        List<? extends GroovyParser.SwitchBlockStatementGroupContext> groupCtxs = ctx.switchBlockStatementGroup();
        Doc body;
        if (groupCtxs.isEmpty()) {
            body = text("{}");
        } else {
            List<Doc> groups = new ArrayList<>();
            for (GroovyParser.SwitchBlockStatementGroupContext g : groupCtxs) {
                groups.add(printSwitchBlockStatementGroup(g));
            }
            body = concat(text("{"), indent(concat(HARDLINE, join(HARDLINE, groups))), HARDLINE, text("}"));
        }
        return concat(text("switch "), visit(ctx.expressionInPar()), text(" "), body);
    }

    private Doc printSwitchBlockStatementGroup(GroovyParser.SwitchBlockStatementGroupContext ctx) {
        List<Doc> labels = new ArrayList<>();
        for (GroovyParser.SwitchLabelContext label : ctx.switchLabel()) {
            labels.add(printSwitchLabel(label));
        }
        Doc labelsDoc = join(HARDLINE, labels);
        if (ctx.blockStatements() == null) {
            return labelsDoc;
        }
        List<Doc> statements = new ArrayList<>();
        for (GroovyParser.BlockStatementContext stmt : ctx.blockStatements().blockStatement()) {
            statements.add(visit(stmt));
        }
        return concat(labelsDoc, indent(concat(HARDLINE, join(HARDLINE, statements))));
    }

    private Doc printSwitchLabel(GroovyParser.SwitchLabelContext ctx) {
        if (ctx.DEFAULT() != null) {
            return text("default:");
        }
        return concat(text("case "), visit(ctx.expression()), text(":"));
    }

    @Override
    public Doc visitLoopStmtAlt(GroovyParser.LoopStmtAltContext ctx) {
        return visit(ctx.loopStatement());
    }

    @Override
    public Doc visitForStmtAlt(GroovyParser.ForStmtAltContext ctx) {
        return concat(text("for ("), printForControl(ctx.forControl()), text(") "), visit(ctx.statement()));
    }

    private Doc printForControl(GroovyParser.ForControlContext ctx) {
        if (ctx.enhancedForControl() != null) {
            return printEnhancedForControl(ctx.enhancedForControl());
        }
        return printClassicalForControl(ctx.classicalForControl());
    }

    private Doc printEnhancedForControl(GroovyParser.EnhancedForControlContext ctx) {
        List<Doc> parts = new ArrayList<>();
        if (ctx.variableModifiersOpt() != null && ctx.variableModifiersOpt().variableModifiers() != null) {
            parts.add(printVariableModifiers(ctx.variableModifiersOpt().variableModifiers()));
            parts.add(text(" "));
        }
        if (ctx.type() != null) {
            parts.add(printType(ctx.type()));
            parts.add(text(" "));
        }
        parts.add(text(ctx.variableDeclaratorId().identifier().getText()));
        parts.add(text(ctx.IN() != null ? " in " : " : "));
        parts.add(visit(ctx.expression()));
        return concat(parts);
    }

    private Doc printClassicalForControl(GroovyParser.ClassicalForControlContext ctx) {
        List<Doc> parts = new ArrayList<>();
        if (ctx.forInit() != null) {
            parts.add(printForInit(ctx.forInit()));
        }
        parts.add(text("; "));
        if (ctx.expression() != null) {
            parts.add(visit(ctx.expression()));
        }
        parts.add(text("; "));
        if (ctx.forUpdate() != null) {
            parts.add(printExpressionList(ctx.forUpdate().expressionList()));
        }
        return concat(parts);
    }

    private Doc printForInit(GroovyParser.ForInitContext ctx) {
        if (ctx.localVariableDeclaration() != null) {
            return visit(ctx.localVariableDeclaration());
        }
        return printExpressionList(ctx.expressionList());
    }

    private Doc printExpressionList(GroovyParser.ExpressionListContext ctx) {
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.ExpressionListElementContext e : ctx.expressionListElement()) {
            parts.add(printExpressionListElement(e));
        }
        return join(text(", "), parts);
    }

    @Override
    public Doc visitWhileStmtAlt(GroovyParser.WhileStmtAltContext ctx) {
        return concat(text("while "), visit(ctx.expressionInPar()), text(" "), visit(ctx.statement()));
    }

    @Override
    public Doc visitDoWhileStmtAlt(GroovyParser.DoWhileStmtAltContext ctx) {
        return concat(
                text("do "),
                visit(ctx.statement()),
                text(" while "),
                visit(ctx.expressionInPar()));
    }

    @Override
    public Doc visitTryCatchStmtAlt(GroovyParser.TryCatchStmtAltContext ctx) {
        return visit(ctx.tryCatchStatement());
    }

    @Override
    public Doc visitTryCatchStatement(GroovyParser.TryCatchStatementContext ctx) {
        if (ctx.resources() != null) {
            throw unsupported(ctx, "try-with-resources");
        }
        List<Doc> parts = new ArrayList<>();
        parts.add(text("try "));
        parts.add(visit(ctx.block()));
        for (GroovyParser.CatchClauseContext catchClause : ctx.catchClause()) {
            parts.add(text(" "));
            parts.add(printCatchClause(catchClause));
        }
        if (ctx.finallyBlock() != null) {
            parts.add(text(" "));
            parts.add(printFinallyBlock(ctx.finallyBlock()));
        }
        return concat(parts);
    }

    private Doc printCatchClause(GroovyParser.CatchClauseContext ctx) {
        List<Doc> parts = new ArrayList<>();
        parts.add(text("catch ("));
        if (ctx.variableModifiersOpt() != null && ctx.variableModifiersOpt().variableModifiers() != null) {
            parts.add(printVariableModifiers(ctx.variableModifiersOpt().variableModifiers()));
            parts.add(text(" "));
        }
        if (ctx.catchType() != null) {
            parts.add(printCatchType(ctx.catchType()));
            parts.add(text(" "));
        }
        parts.add(text(ctx.identifier().getText()));
        parts.add(text(") "));
        parts.add(visit(ctx.block()));
        return concat(parts);
    }

    private Doc printCatchType(GroovyParser.CatchTypeContext ctx) {
        List<Doc> parts = new ArrayList<>();
        for (GroovyParser.QualifiedClassNameContext t : ctx.qualifiedClassName()) {
            parts.add(text(t.getText()));
        }
        return join(text(" | "), parts);
    }

    private Doc printFinallyBlock(GroovyParser.FinallyBlockContext ctx) {
        return concat(text("finally "), visit(ctx.block()));
    }

    @Override
    public Doc visitBreakStmtAlt(GroovyParser.BreakStmtAltContext ctx) {
        GroovyParser.BreakStatementContext b = ctx.breakStatement();
        return b.identifier() != null ? concat(text("break "), text(b.identifier().getText())) : text("break");
    }

    @Override
    public Doc visitContinueStmtAlt(GroovyParser.ContinueStmtAltContext ctx) {
        GroovyParser.ContinueStatementContext c = ctx.continueStatement();
        return c.identifier() != null ? concat(text("continue "), text(c.identifier().getText())) : text("continue");
    }

    @Override
    public Doc visitThrowStmtAlt(GroovyParser.ThrowStmtAltContext ctx) {
        return concat(text("throw "), visit(ctx.expression()));
    }

    @Override
    public Doc visitAssertStmtAlt(GroovyParser.AssertStmtAltContext ctx) {
        return visit(ctx.assertStatement());
    }

    @Override
    public Doc visitAssertStatement(GroovyParser.AssertStatementContext ctx) {
        List<Doc> parts = new ArrayList<>();
        parts.add(text("assert "));
        parts.add(visit(ctx.ce));
        if (ctx.me != null) {
            // Groovy allows both 'assert cond : message' and 'assert cond, message' — preserve
            // whichever separator the source actually used rather than normalizing.
            parts.add(ctx.COLON() != null ? text(" : ") : text(", "));
            parts.add(visit(ctx.me));
        }
        return concat(parts);
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
        // Command-chain syntax has two shapes. The paren-less named/positional-argument shorthand
        // ('foo bar: 1, baz: 2' or 'foo bar, baz') attaches an enhancedArgumentListInPar() directly
        // to this node — same grammar element printArguments() already knows how to print, just
        // without parens (kept paren-less: that's usually a deliberate DSL-style choice). The true
        // multi-word chain ('foo bar baz', via commandArgument(), each argument itself able to
        // recurse into further command arguments) is a distinct, more elaborate construct still out
        // of scope.
        if (!ctx.commandArgument().isEmpty()) {
            throw unsupported(ctx, "multi-word command-chain expressions (e.g. 'foo bar baz')");
        }
        Doc base = visit(ctx.expression());
        if (ctx.enhancedArgumentListInPar() == null) {
            return base;
        }
        List<Doc> args = new ArrayList<>();
        for (GroovyParser.EnhancedArgumentListElementContext e : ctx.enhancedArgumentListInPar().enhancedArgumentListElement()) {
            args.add(printArgumentElement(e));
        }
        return concat(base, text(" "), join(text(", "), args));
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
    public Doc visitShiftExprAlt(GroovyParser.ShiftExprAltContext ctx) {
        if (ctx.rangeOp != null) {
            // Unlike the other binary operators, ranges conventionally have no surrounding spaces
            // (1..10, not 1 .. 10).
            return concat(visit(ctx.left), text(ctx.rangeOp.getText()), visit(ctx.right));
        }
        // <</>>/>>> are each lexed as 2-3 adjacent LT/GT tokens rather than one shift token (to
        // avoid ambiguity with nested generics like Map<List<String>>), so reconstruct the
        // operator text from how many of each are present rather than from the dlOp/tgOp/dgOp
        // fields, whose exact semantics aren't documented anywhere reachable here.
        String op;
        if (ctx.LT().size() == 2) {
            op = "<<";
        } else if (ctx.GT().size() == 2) {
            op = ">>";
        } else if (ctx.GT().size() == 3) {
            op = ">>>";
        } else {
            throw unsupported(ctx, "shift expressions");
        }
        return binaryDoc(visit(ctx.left), op, visit(ctx.right));
    }

    @Override
    public Doc visitConditionalExprAlt(GroovyParser.ConditionalExprAltContext ctx) {
        if (ctx.ELVIS() != null) {
            return binaryDoc(visit(ctx.con), "?:", visit(ctx.fb));
        }
        return group(
                concat(
                        visit(ctx.con),
                        indent(concat(LINE, text("? "), visit(ctx.tb), LINE, text(": "), visit(ctx.fb)))));
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
        if (ctx.indexPropertyArgs() != null) {
            return printIndexPropertyArgs(ctx.indexPropertyArgs());
        }
        if (ctx.closureOrLambdaExpression() != null) {
            // A bare trailing closure argument, e.g. `list.each { it * 2 }` — no parens/arguments()
            // at all in the CST for this form; print exactly as written, no paren-adding/dropping.
            return concat(text(" "), visit(ctx.closureOrLambdaExpression()));
        }
        throw unsupported(ctx, "named-property/new path elements");
    }

    private Doc printIndexPropertyArgs(GroovyParser.IndexPropertyArgsContext ctx) {
        String open = ctx.SAFE_INDEX() != null ? "?[" : "[";
        List<Doc> indices = new ArrayList<>();
        for (GroovyParser.ExpressionListElementContext e : ctx.expressionList().expressionListElement()) {
            indices.add(printExpressionListElement(e));
        }
        return group(
                concat(
                        text(open),
                        indent(concat(SOFTLINE, join(concat(text(","), LINE), indices))),
                        SOFTLINE,
                        text("]")));
    }

    private Doc printExpressionListElement(GroovyParser.ExpressionListElementContext ctx) {
        Doc doc = visit(ctx.expression());
        return ctx.MUL() != null ? concat(text("*"), doc) : doc;
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
            return printExpressionListElement(ctx.expressionListElement());
        }
        if (ctx.mapEntry() != null) {
            return printMapEntry(ctx.mapEntry());
        }
        throw unsupported(ctx, "lambda arguments");
    }

    private Doc printMapEntry(GroovyParser.MapEntryContext ctx) {
        if (ctx.MUL() != null) {
            throw unsupported(ctx, "spread map entries (*:map)");
        }
        return concat(printMapEntryLabel(ctx.mapEntryLabel()), text(": "), visit(ctx.expression()));
    }

    private Doc printMapEntryLabel(GroovyParser.MapEntryLabelContext ctx) {
        if (ctx.keywords() != null) {
            return text(ctx.keywords().getText());
        }
        return visit(ctx.primary());
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
    public Doc visitThisPrmrAlt(GroovyParser.ThisPrmrAltContext ctx) {
        return text("this");
    }

    @Override
    public Doc visitSuperPrmrAlt(GroovyParser.SuperPrmrAltContext ctx) {
        return text("super");
    }

    @Override
    public Doc visitBuiltInTypePrmrAlt(GroovyParser.BuiltInTypePrmrAltContext ctx) {
        return text(ctx.builtInType().getText());
    }

    @Override
    public Doc visitNewPrmrAlt(GroovyParser.NewPrmrAltContext ctx) {
        return printCreator(ctx.creator());
    }

    private Doc printCreator(GroovyParser.CreatorContext ctx) {
        if (ctx.anonymousInnerClassDeclaration() != null) {
            throw unsupported(ctx, "anonymous inner class bodies");
        }
        if (!ctx.dim().isEmpty() || ctx.arrayInitializer() != null) {
            throw unsupported(ctx, "array creation expressions");
        }
        GroovyParser.CreatedNameContext name = ctx.createdName();
        String typeName =
                name.primitiveType() != null ? name.primitiveType().getText() : name.qualifiedClassName().getText();
        Doc typeArgs = name.typeArgumentsOrDiamond() == null ? NIL : printTypeArgumentsOrDiamond(name.typeArgumentsOrDiamond());
        return concat(text("new "), text(typeName), typeArgs, printArguments(ctx.arguments()));
    }

    private Doc printTypeArgumentsOrDiamond(GroovyParser.TypeArgumentsOrDiamondContext ctx) {
        return ctx.typeArguments() == null ? text("<>") : printTypeArguments(ctx.typeArguments());
    }

    @Override
    public Doc visitListPrmrAlt(GroovyParser.ListPrmrAltContext ctx) {
        return printList(ctx.list());
    }

    private Doc printList(GroovyParser.ListContext ctx) {
        if (ctx.expressionList() == null) {
            return text("[]");
        }
        List<Doc> elements = new ArrayList<>();
        for (GroovyParser.ExpressionListElementContext e : ctx.expressionList().expressionListElement()) {
            elements.add(printExpressionListElement(e));
        }
        return group(
                concat(
                        text("["),
                        indent(concat(SOFTLINE, join(concat(text(","), LINE), elements))),
                        ifBreak(text(","), NIL),
                        SOFTLINE,
                        text("]")));
    }

    @Override
    public Doc visitMapPrmrAlt(GroovyParser.MapPrmrAltContext ctx) {
        return printMap(ctx.map());
    }

    private Doc printMap(GroovyParser.MapContext ctx) {
        if (ctx.COLON() != null) {
            return text("[:]");
        }
        List<Doc> entries = new ArrayList<>();
        for (GroovyParser.MapEntryContext e : ctx.mapEntryList().mapEntry()) {
            entries.add(printMapEntry(e));
        }
        return group(
                concat(
                        text("["),
                        indent(concat(SOFTLINE, join(concat(text(","), LINE), entries))),
                        ifBreak(text(","), NIL),
                        SOFTLINE,
                        text("]")));
    }

    @Override
    public Doc visitParExpression(GroovyParser.ParExpressionContext ctx) {
        return visit(ctx.expressionInPar());
    }

    @Override
    public Doc visitClosureOrLambdaExpressionPrmrAlt(GroovyParser.ClosureOrLambdaExpressionPrmrAltContext ctx) {
        return visit(ctx.closureOrLambdaExpression());
    }

    @Override
    public Doc visitClosureOrLambdaExpression(GroovyParser.ClosureOrLambdaExpressionContext ctx) {
        if (ctx.closure() == null) {
            throw unsupported(ctx, "Java-style lambda expressions (a -> a + 1)");
        }
        return visit(ctx.closure());
    }

    @Override
    public Doc visitClosure(GroovyParser.ClosureContext ctx) {
        Doc header = NIL;
        if (ctx.ARROW() != null) {
            Doc params = ctx.formalParameterList() == null ? NIL : printCommaJoinedParams(ctx.formalParameterList());
            header = concat(text(" "), params, text(" ->"));
        }

        List<? extends GroovyParser.BlockStatementContext> statements =
                ctx.blockStatementsOpt().blockStatements() == null
                        ? List.of()
                        : ctx.blockStatementsOpt().blockStatements().blockStatement();
        List<CommentAttacher.Item> items = commentAttacher.attach(
                statements, ctx.LBRACE().getSymbol().getTokenIndex(), ctx.RBRACE().getSymbol().getTokenIndex());

        if (items.isEmpty()) {
            return concat(text("{"), header, text("}"));
        }
        // A single-statement closure with no leading/dangling comments is kept on one line when it
        // fits — this is the extremely common `list.each { it * 2 }` / `.findAll { it > 0 }` case,
        // and forcing it to always break would surprise most Groovy authors. Anything more (a
        // second statement, or any standalone comment) can't safely be flattened — Groovy has no
        // implicit multi-statement-per-line separator — so it always breaks.
        if (items.size() == 1 && items.get(0).isNode()) {
            Doc body = printAttachedItem(items.get(0));
            return group(concat(text("{"), header, indent(concat(LINE, body)), LINE, text("}")));
        }
        return concat(
                text("{"), header, indent(concat(HARDLINE, printAttachedItems(items))), HARDLINE, text("}"));
    }

    private Doc printCommaJoinedParams(GroovyParser.FormalParameterListContext ctx) {
        List<Doc> params = new ArrayList<>();
        for (GroovyParser.FormalParameterContext p : ctx.formalParameter()) {
            params.add(printFormalParameter(p));
        }
        return join(text(", "), params);
    }

    @Override
    public Doc visitGstringPrmrAlt(GroovyParser.GstringPrmrAltContext ctx) {
        // GStrings (interpolated strings) are printed verbatim from the raw source, not
        // reconstructed from the parse tree: Groovy's WS tokens are lexer-skipped (never appear in
        // the token stream at all, see GroovyLexer.g4's `WS -> skip`), so any reconstruction via
        // ctx.getText() would silently normalize away the user's original spacing inside `${...}`.
        // Slicing the raw source by character offset is the only way to reproduce it exactly.
        return printVerbatimSourceSpan(ctx.gstring());
    }

    private Doc printVerbatimSourceSpan(GroovyParser.GroovyParserRuleContext ctx) {
        int startChar = ctx.getStart().getStartIndex();
        int stopChar = ctx.getStop().getStopIndex();
        return text(source.substring(startChar, stopChar + 1));
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

    // ---- Comment/blank-line interleaving --------------------------------------------------------

    /** Renders a sequence of {@link CommentAttacher.Item}s, preserving up to one blank line between them. */
    private Doc printAttachedItems(List<CommentAttacher.Item> items) {
        List<Doc> parts = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CommentAttacher.Item item = items.get(i);
            if (i > 0) {
                parts.add(item.blankBefore() ? concat(HARDLINE, HARDLINE) : HARDLINE);
            }
            parts.add(printAttachedItem(item));
        }
        return concat(parts);
    }

    private Doc printAttachedItem(CommentAttacher.Item item) {
        if (item.isComment()) {
            return text(item.standaloneComment().text());
        }
        Doc doc = visit(item.node());
        if (item.trailingComment() != null) {
            doc = concat(doc, lineSuffix(text(" " + item.trailingComment().text())));
        }
        return doc;
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
