package com.alpha.tooling;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

public class OneParameterPerLine extends Recipe {

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
                final var reformatted = OnePerLineSupport.spreadAcrossLines(parameters, m.getPrefix());
                return reformatted == parameters ? m : m.withParameters(reformatted);
            }
        };
    }
}
