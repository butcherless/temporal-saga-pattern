package com.alpha.saga.common.util;

/**
 * The one place a string is checked for null-or-blank across every module — replaces the
 * repeated {@code x == null || x.isBlank()} chains and unguarded {@code x.isBlank()} calls
 * (which NPE on a null {@code x}) with a single null-safe check.
 */
public final class StringUtils {

    private StringUtils() {
    }

    /** Whether {@code value} is {@code null}, empty, or contains only whitespace. */
    public static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
