package com.masspos.billing;

import java.time.LocalDate;

/** Terminal-prefixed GST invoice numbers: {@code T1-2627-00001}. */
public final class InvoiceNumbers {

    /** GST Rule 46(b): at most 16 characters, only letters, digits, '-' and '/'. */
    public static final int MAX_LENGTH = 16;

    private InvoiceNumbers() {
    }

    /** Indian financial year (April to March) as four digits: 2026-09-11 → "2627", 2027-04-01 → "2728". */
    public static String financialYearCode(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return "%02d%02d".formatted(startYear % 100, (startYear + 1) % 100);
    }

    /**
     * Pads the sequence to five digits and widens past 99,999 instead of wrapping, so a busy till
     * never runs out of numbers: T1-2627-100000 is still unique and within 16 characters.
     */
    public static String format(String terminalCode, String financialYear, long sequenceNo) {
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("Invoice sequence starts at 1, got " + sequenceNo);
        }
        String number = "%s-%s-%05d".formatted(terminalCode, financialYear, sequenceNo);
        if (number.length() > MAX_LENGTH) {
            throw new IllegalStateException("Invoice number exceeds GST's 16-character limit: " + number);
        }
        return number;
    }
}
