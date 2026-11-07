package com.masspos.billing;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Terminal-prefixed GST invoice numbers, one series a till a day: {@code T1-071126-0001}.
 *
 * <p>The day is in the number because the running number starts again at 1 every morning, and
 * Rule 46(b) wants a serial that is unique for the whole financial year. The rule allows that
 * serial to run "in one or multiple series", and a series a till a day is exactly that; without
 * the date in it, the first bill of every day of the year would be numbered the same.
 *
 * <p>Tills shipped before this issued {@code T1-2627-00001}, a series a till a financial year.
 * Both shapes are three parts, so a bill taken under either still reads and still searches.
 */
public final class InvoiceNumbers {

    /** GST Rule 46(b): at most 16 characters, only letters, digits, '-' and '/'. */
    public static final int MAX_LENGTH = 16;

    /** 2026-11-07 -> "071126". The way a date is read off a bill in a shop here. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("ddMMyy");

    private InvoiceNumbers() {
    }

    /** The day's series, which the running number restarts within: 2026-11-07 → "071126". */
    public static String dayCode(LocalDate date) {
        return DAY.format(date);
    }

    /** Indian financial year (April to March) as four digits: 2026-09-11 → "2627", 2027-04-01 → "2728". */
    public static String financialYearCode(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return "%02d%02d".formatted(startYear % 100, (startYear + 1) % 100);
    }

    /**
     * The day's bill number: {@code T1-071126-0001}. Padded to four digits and widening past 9,999
     * instead of wrapping, so a till that somehow takes ten thousand bills in one day keeps going -
     * at 15 characters there is room for a fifth digit inside Rule 46's sixteen.
     */
    public static String format(String terminalCode, LocalDate date, long dailyNo) {
        if (dailyNo < 1) {
            throw new IllegalArgumentException("The day's first bill is number 1, got " + dailyNo);
        }
        return checked("%s-%s-%04d".formatted(terminalCode, dayCode(date), dailyNo));
    }

    /** Returns run in their own series for the day, marked R: {@code T1-071126-R001}. */
    public static String formatCreditNote(String terminalCode, LocalDate date, long dailyNo) {
        if (dailyNo < 1) {
            throw new IllegalArgumentException("The day's first return is number 1, got " + dailyNo);
        }
        return checked("%s-%s-R%03d".formatted(terminalCode, dayCode(date), dailyNo));
    }

    /**
     * The counter a document was issued on, taken back out of its number: the {@code T1} of
     * {@code T1-071126-0001}. Empty for anything not in that shape, such as a number brought in
     * from another system.
     */
    public static String counterOf(String number) {
        String[] parts = split(number);
        return parts == null ? "" : parts[0];
    }

    /**
     * The running number on its own: the {@code 0001} of {@code T1-071126-0001}, or {@code R001}
     * of a return. What a person is shown and what they say out loud - the counter and the day are
     * beside it on the screen and on the paper, where they do not have to be read past to find the
     * one thing that identifies the bill on the till and the day it was taken on.
     *
     * <p>The number of record, filed and stored, is still the whole {@code T1-071126-0001}. A bill
     * from a till shipped before the day series reads the same way: {@code 00001} of
     * {@code T1-2627-00001}.
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
