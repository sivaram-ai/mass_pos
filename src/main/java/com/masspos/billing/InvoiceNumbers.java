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
        return checked("%s-%s-%05d".formatted(terminalCode, financialYear, sequenceNo));
    }

    /** Credit notes run in their own series, marked R for return: {@code T1-2627-R0001}. */
    public static String formatCreditNote(String terminalCode, String financialYear, long sequenceNo) {
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("Credit note sequence starts at 1, got " + sequenceNo);
        }
        return checked("%s-%s-R%04d".formatted(terminalCode, financialYear, sequenceNo));
    }

    /**
     * The counter a document was issued on, taken back out of its number: the {@code T1} of
     * {@code T1-2627-00001}. Empty for anything not in that shape, such as a number brought in
     * from another system.
     */
    public static String counterOf(String number) {
        String[] parts = split(number);
        return parts == null ? "" : parts[0];
    }

    /**
     * The running number on its own: the {@code 00001} of {@code T1-2627-00001}, or {@code R0001}
     * of a credit note. What a person is shown and what they say out loud - the counter and the
     * financial year are beside it on the screen and on the paper, where they do not have to be
     * read past to find the one thing that identifies the bill on the till it was taken on.
     *
     * <p>Rule 46(b) allows a serial in multiple series, which is what one series per counter is;
     * the number of record, filed and stored, is still the whole {@code T1-2627-00001}.
     */
    public static String serialOf(String number) {
        String[] parts = split(number);
        return parts == null ? number : parts[2];
    }

    /** Null unless the number is this till's own three-part shape. */
    private static String[] split(String number) {
        if (number == null) {
            return null;
        }
        String[] parts = number.split("-");
        return parts.length == 3 ? parts : null;
    }

    private static String checked(String number) {
        if (number.length() > MAX_LENGTH) {
            throw new IllegalStateException("Document number exceeds GST's 16-character limit: " + number);
        }
        return number;
    }
}
