package com.company.tooling;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

public class OneParameterPerLine extends Recipe {

    private static final String CONTINUATION_INDENT = "        ";

    @Override
    public String getDisplayName() {
        return "One parameter per line";
    }

    @Override
    public String getDescription() {
        return """
                For method and constructor declarations with two or more parameters, puts every \
                parameter after the first on its own line, indented one continuation level past the \
                declaration. Declarations with zero or one parameter, method bodies, comments, and \
                everything else are left untouched.""";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(final J.MethodDeclaration method, final ExecutionContext ctx) {
                final var m = super.visitMethodDeclaration(method, ctx);
                final var parameters = m.getParameters();
                if (parameters.size() < 2) {
                    return m;
                }

                final var onOwnLine = Space.format("\n" + indentationOf(m.getPrefix()) + CONTINUATION_INDENT);
                // ListUtils.map returns the same list reference untouched when every mapped
                // element is reference-equal to its input, so this stays a pure, allocation-free
                // no-op whenever the method is already formatted.
                final var reformatted = ListUtils.map(parameters, (index, parameter) ->
                        index == 0
                                || !parameter.getPrefix().getComments().isEmpty()
                                || parameter.getPrefix().equals(onOwnLine)
                                ? parameter
                                : parameter.withPrefix(onOwnLine));

                return reformatted == parameters ? m : m.withParameters(reformatted);
            }

            private String indentationOf(final Space prefix) {
                final var whitespace = prefix.getWhitespace();
                final var lastNewline = whitespace.lastIndexOf('\n');
                return lastNewline < 0 ? "" : whitespace.substring(lastNewline + 1);
            }
        };
    }
}
