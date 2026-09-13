package com.masspos.billing;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceNumbersTest {

    @Test
    void financialYearRunsAprilToMarch() {
        assertThat(InvoiceNumbers.financialYearCode(LocalDate.of(2026, 9, 11))).isEqualTo("2627");
        assertThat(InvoiceNumbers.financialYearCode(LocalDate.of(2027, 3, 31))).isEqualTo("2627");
        assertThat(InvoiceNumbers.financialYearCode(LocalDate.of(2027, 4, 1))).isEqualTo("2728");
        assertThat(InvoiceNumbers.financialYearCode(LocalDate.of(2099, 5, 1))).isEqualTo("9900");
    }

    @Test
    void formatsTerminalPrefixedNumbers() {
        assertThat(InvoiceNumbers.format("T1", "2627", 1)).isEqualTo("T1-2627-00001");
        assertThat(InvoiceNumbers.format("T12", "2627", 99_999)).isEqualTo("T12-2627-99999");
    }

    @Test
    void widensInsteadOfWrappingOnBusyTills() {
        assertThat(InvoiceNumbers.format("T1", "2627", 100_000)).isEqualTo("T1-2627-100000");
        assertThat(InvoiceNumbers.format("T12", "2627", 1_000_000)).hasSize(InvoiceNumbers.MAX_LENGTH);
    }

    @Test
    void neverExceedsTheGstLengthLimit() {
        assertThatThrownBy(() -> InvoiceNumbers.format("T12", "2627", 10_000_000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceNumbers.format("T1", "2627", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
