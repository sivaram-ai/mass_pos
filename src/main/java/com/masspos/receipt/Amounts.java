package com.masspos.receipt;

import java.math.BigDecimal;

/** Receipt formatting for the integer units used in storage. */
final class Amounts {

    private Amounts() {
    }

    /** 20000 → "200.00", -150 → "-1.50". */
    static String rupees(long paise) {
        long abs = Math.abs(paise);
        return (paise < 0 ? "-" : "") + abs / 100 + "." + "%02d".formatted(abs % 100);
    }

    /** 2000 → "2", 1250 → "1.25". */
    static String quantity(long milliUnits) {
        return BigDecimal.valueOf(milliUnits, 3).stripTrailingZeros().toPlainString();
    }

    /** 1800 → "18%", 250 → "2.5%". */
    static String percent(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + "%";
    }
}
