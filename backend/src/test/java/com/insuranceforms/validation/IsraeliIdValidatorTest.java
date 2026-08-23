package com.insuranceforms.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsraeliIdValidatorTest {

    @Test
    void acceptsValidCheckDigit() {
        assertThat(IsraeliIdValidator.isValid("123456782")).isTrue();
    }

    @Test
    void rejectsInvalidCheckDigit() {
        assertThat(IsraeliIdValidator.isValid("123456783")).isFalse();
    }

    @Test
    void rejectsNonDigitInput() {
        assertThat(IsraeliIdValidator.isValid("12345678X")).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(IsraeliIdValidator.isValid(null)).isFalse();
    }
}
