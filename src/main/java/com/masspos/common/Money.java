package com.masspos.common;

/** Stored amounts as the plain figures used in messages. */
public final class Money {

    private Money() {
    }

    /** 20000 paise → "200.00". */
    public static String rupees(long paise) {
        long abs = Math.abs(paise);
        return (paise < 0 ? "-" : "") + (abs / 100) + "." + "%02d".formatted(abs % 100);
    }

    /** 1000 milli-units → "1", 1250 → "1.25". */
    public static String quantity(long milliUnits) {
        return java.math.BigDecimal.valueOf(milliUnits, 3).stripTrailingZeros().toPlainString();
    }
}
