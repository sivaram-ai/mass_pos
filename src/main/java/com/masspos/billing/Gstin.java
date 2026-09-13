package com.masspos.billing;

import java.util.regex.Pattern;

/**
 * GSTIN: 2-digit state code, 10-character PAN, entity number, one more character (normally 'Z'),
 * and a mod-36 check character. The check character catches the one-character typos that would
 * otherwise reach a tax invoice and cost the buyer their input tax credit.
 */
public final class Gstin {

    private static final String CODE_POINTS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern FORMAT = Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z][0-9A-Z][0-9A-Z]");

    private Gstin() {
    }

    public static boolean isValid(String gstin) {
        return gstin != null && FORMAT.matcher(gstin).matches()
                && gstin.charAt(14) == checkCharacter(gstin.substring(0, 14));
    }

    /** The state the registration belongs to, e.g. "27" for Maharashtra. */
    public static String stateCode(String gstin) {
        return gstin.substring(0, 2);
    }

    static char checkCharacter(String first14) {
        int sum = 0;
        for (int i = 0; i < first14.length(); i++) {
            int product = CODE_POINTS.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CODE_POINTS.charAt((36 - sum % 36) % 36);
    }
}
