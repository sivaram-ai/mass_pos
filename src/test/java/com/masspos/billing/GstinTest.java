package com.masspos.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GstinTest {

    @Test
    void checkCharacterMatchesThePublishedSample() {
        assertThat(Gstin.checkCharacter("27AAPFU0939F1Z")).isEqualTo('V');
    }

    @Test
    void acceptsGstinsWithTheRightCheckCharacter() {
        assertThat(Gstin.isValid("27AAPFU0939F1ZV")).isTrue();
        assertThat(Gstin.isValid("29AABCU9603R1ZJ")).isTrue();
    }

    @Test
    void rejectsSingleCharacterTypos() {
        assertThat(Gstin.isValid("27AAPFU0939F1ZW")).isFalse(); // check character
        assertThat(Gstin.isValid("28AAPFU0939F1ZV")).isFalse(); // state code
        assertThat(Gstin.isValid("27AAPFU0939F1ZV".replace("0939", "0993"))).isFalse(); // swapped digits
    }

    @Test
    void rejectsMalformedInput() {
        assertThat(Gstin.isValid("27aapfu0939f1zv")).isFalse();
        assertThat(Gstin.isValid("27AAPFU0939F1Z")).isFalse();
        assertThat(Gstin.isValid("")).isFalse();
        assertThat(Gstin.isValid(null)).isFalse();
    }

    @Test
    void stateCodeIsTheFirstTwoDigits() {
        assertThat(Gstin.stateCode("29AABCU9603R1ZJ")).isEqualTo("29");
    }
}
