package com.company.tooling;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

public class OneRecordComponentPerLine extends Recipe {

    @Override
    public String getDisplayName() {
        return "One record component per line";
    }

    @Override
    public String getDescription() {
        return """
                For record declarations with two or more components, puts every component after \
                the first on its own line, indented one continuation level past the declaration. \
                Records with zero or one component, non-record types, comments, and everything \
                else are left untouched.""";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(final J.ClassDeclaration classDecl, final ExecutionContext ctx) {
                final var c = super.visitClassDeclaration(classDecl, ctx);
                if (c.getKind() != J.ClassDeclaration.Kind.Type.Record) {
                    return c;
                }

                final var components = c.getPrimaryConstructor();
                if (components == null) {
                    return c;
                }

                final var reformatted = OnePerLineSupport.spreadAcrossLines(components, c.getPrefix());
                return reformatted == components ? c : c.withPrimaryConstructor(reformatted);
            }
        };
    }
}
