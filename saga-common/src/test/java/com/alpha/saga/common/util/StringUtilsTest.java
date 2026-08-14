package com.alpha.saga.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringUtilsTest {

    @Test
    void isBlank_isTrueForNull() {
        assertThat(StringUtils.isBlank(null)).isTrue();
    }

    @Test
    void isBlank_isTrueForEmpty() {
        assertThat(StringUtils.isBlank("")).isTrue();
    }

    @Test
    void isBlank_isTrueForWhitespaceOnly() {
        assertThat(StringUtils.isBlank("   ")).isTrue();
    }

    @Test
    void isBlank_isFalseForNonBlankValue() {
        assertThat(StringUtils.isBlank("SKU-001")).isFalse();
    }

    @Test
    void isBlank_isFalseForValueWithSurroundingWhitespace() {
        assertThat(StringUtils.isBlank(" SKU-001 ")).isFalse();
    }
}
