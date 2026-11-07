package com.masspos.billing;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceNumbersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);

    @Test
    void financialYearRunsAprilToMarch() {
        assertThat(InvoiceNumbers.financialYearCode(LocalDate.of(2026, 9, 11))).isEqualTo("2627");
        assertThat(InvoiceNumbers.financialYearCode(LocalDate.of(2027, 3, 31))).isEqualTo("2627");
        assertThat(InvoiceNumbers.financialYearCode(LocalDate.of(2027, 4, 1))).isEqualTo("2728");
        assertThat(InvoiceNumbers.financialYearCode(LocalDate.of(2099, 5, 1))).isEqualTo("9900");
    }

    @Test
    void theDayIsInTheNumberAndTheRunningNumberStartsAtOne() {
        assertThat(InvoiceNumbers.format("T1", DAY, 1)).isEqualTo("T1-071126-0001");
        assertThat(InvoiceNumbers.format("T12", DAY, 9_999)).isEqualTo("T12-071126-9999");
        assertThat(InvoiceNumbers.formatCreditNote("T1", DAY, 1)).isEqualTo("T1-071126-R001");
    }

    /**
     * Why the date is in there at all: Rule 46(b) wants a serial unique for the financial year,
     * and the running number starts again every morning. Without the day, the first bill of every
     * day of the year would carry the same number.
     */
    @Test
    void twoDaysBothStartAtOneAndStillHaveDifferentNumbers() {
        String first = InvoiceNumbers.format("T1", DAY, 1);
        String nextMorning = InvoiceNumbers.format("T1", DAY.plusDays(1), 1);

        assertThat(first).isNotEqualTo(nextMorning);
        assertThat(InvoiceNumbers.serialOf(first)).isEqualTo(InvoiceNumbers.serialOf(nextMorning)).isEqualTo("0001");
    }

    @Test
    void widensInsteadOfWrappingOnABusyDay() {
        assertThat(InvoiceNumbers.format("T1", DAY, 10_000)).isEqualTo("T1-071126-10000");
        assertThat(InvoiceNumbers.format("T12", DAY, 99_999)).hasSize(InvoiceNumbers.MAX_LENGTH);
    }

    /**
     * What a person is shown: the running number on its own, with the counter beside it rather
     * than stuck to the front of it. The whole string stays the number of record.
     */
    @Test
    void takesTheCounterAndTheRunningNumberBackOutOfANumber() {
        assertThat(InvoiceNumbers.serialOf("T1-071126-0001")).isEqualTo("0001");
        assertThat(InvoiceNumbers.counterOf("T1-071126-0001")).isEqualTo("T1");
        assertThat(InvoiceNumbers.serialOf("T12-071126-R004")).isEqualTo("R004");
        assertThat(InvoiceNumbers.counterOf("T12-071126-R004")).isEqualTo("T12");
    }

    /**
     * A till upgraded from the financial-year series has bills numbered the old way, and they
     * have to keep reading on the screen and in the audit trail exactly as they did.
     */
    @Test
    void aBillFromBeforeTheDaySeriesStillReads() {
        assertThat(InvoiceNumbers.serialOf("T1-2627-00001")).isEqualTo("00001");
        assertThat(InvoiceNumbers.counterOf("T1-2627-00001")).isEqualTo("T1");
        assertThat(InvoiceNumbers.serialOf("T12-2627-R0004")).isEqualTo("R0004");
        assertThat(InvoiceNumbers.counterOf("T12-2627-R0004")).isEqualTo("T12");
    }

    /** A number from somewhere else is shown as it is, rather than cut up on a guess. */
    @Test
    void leavesANumberThatIsNotThisTillsShapeAlone() {
        assertThat(InvoiceNumbers.serialOf("INV/2026/0042")).isEqualTo("INV/2026/0042");
        assertThat(InvoiceNumbers.counterOf("INV/2026/0042")).isEmpty();
        assertThat(InvoiceNumbers.serialOf(null)).isNull();
    }

    @Test
    void neverExceedsTheGstLengthLimit() {
        assertThatThrownBy(() -> InvoiceNumbers.format("T12", DAY, 1_000_000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceNumbers.format("T1", DAY, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
