package com.alpha.tooling;

import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;

import java.util.List;

/**
 * Shared whitespace-only reformatting for the "one X per line" recipes in this module:
 * given a padded element list (method/constructor parameters, record components, ...)
 * and the prefix of the declaration that owns it, puts every element after the first on
 * its own line, one continuation level past the declaration's own indentation.
 */
final class OnePerLineSupport {

    private static final String CONTINUATION_INDENT = "        ";

    private OnePerLineSupport() {
    }

    static List<Statement> spreadAcrossLines(final List<Statement> elements, final Space declarationPrefix) {
        if (elements.size() < 2) {
            return elements;
        }

        final var onOwnLine = Space.format("\n" + indentationOf(declarationPrefix) + CONTINUATION_INDENT);
        // ListUtils.map returns the same list reference untouched when every mapped element
        // is reference-equal to its input, so this stays a pure, allocation-free no-op
        // whenever the declaration is already formatted.
        return ListUtils.map(elements, (index, element) ->
                index == 0
                        || !element.getPrefix().getComments().isEmpty()
                        || element.getPrefix().equals(onOwnLine)
                        ? element
                        : element.withPrefix(onOwnLine));
    }

    private static String indentationOf(final Space prefix) {
        final var whitespace = prefix.getWhitespace();
        final var lastNewline = whitespace.lastIndexOf('\n');
        return lastNewline < 0 ? "" : whitespace.substring(lastNewline + 1);
    }
}
