package com.company.tooling;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;

import java.util.List;

public class OneParameterPerLine extends Recipe {

    private static final String CONTINUATION_INDENT = "        ";

    @Override
    public String getDisplayName() {
        return "One parameter per line";
    }

    @Override
    public String getDescription() {
        return "For method and constructor declarations with two or more parameters, puts every " +
                "parameter after the first on its own line, indented one continuation level past the " +
                "declaration. Declarations with zero or one parameter, method bodies, comments, and " +
                "everything else are left untouched.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

                List<Statement> parameters = m.getParameters();
                if (parameters.size() < 2) {
                    return m;
                }

                Space onOwnLine = Space.format("\n" + indentationOf(m.getPrefix()) + CONTINUATION_INDENT);
                boolean[] changed = {false};

                List<Statement> reformatted = ListUtils.map(parameters, (index, parameter) -> {
                    if (index == 0
                            || !parameter.getPrefix().getComments().isEmpty()
                            || parameter.getPrefix().equals(onOwnLine)) {
                        return parameter;
                    }
                    changed[0] = true;
                    return parameter.withPrefix(onOwnLine);
                });

                return changed[0] ? m.withParameters(reformatted) : m;
            }

            private String indentationOf(Space prefix) {
                String whitespace = prefix.getWhitespace();
                int lastNewline = whitespace.lastIndexOf('\n');
                return lastNewline < 0 ? "" : whitespace.substring(lastNewline + 1);
            }
        };
    }
}
