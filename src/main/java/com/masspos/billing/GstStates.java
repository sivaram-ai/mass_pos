package com.masspos.billing;

import java.util.Map;
import java.util.Optional;

import static java.util.Map.entry;

/**
 * GST state codes (the first two digits of a GSTIN), as used for place of supply. Rule 46 requires
 * the state's name next to the place of supply on inter-state invoices.
 */
public final class GstStates {

    private static final Map<String, String> NAMES = Map.ofEntries(
            entry("01", "Jammu and Kashmir"),
            entry("02", "Himachal Pradesh"),
            entry("03", "Punjab"),
            entry("04", "Chandigarh"),
            entry("05", "Uttarakhand"),
            entry("06", "Haryana"),
            entry("07", "Delhi"),
            entry("08", "Rajasthan"),
            entry("09", "Uttar Pradesh"),
            entry("10", "Bihar"),
            entry("11", "Sikkim"),
            entry("12", "Arunachal Pradesh"),
            entry("13", "Nagaland"),
            entry("14", "Manipur"),
            entry("15", "Mizoram"),
            entry("16", "Tripura"),
            entry("17", "Meghalaya"),
            entry("18", "Assam"),
            entry("19", "West Bengal"),
            entry("20", "Jharkhand"),
            entry("21", "Odisha"),
            entry("22", "Chhattisgarh"),
            entry("23", "Madhya Pradesh"),
            entry("24", "Gujarat"),
            entry("25", "Daman and Diu"),
            entry("26", "Dadra and Nagar Haveli and Daman and Diu"),
            entry("27", "Maharashtra"),
            entry("28", "Andhra Pradesh (before division)"),
            entry("29", "Karnataka"),
            entry("30", "Goa"),
            entry("31", "Lakshadweep"),
            entry("32", "Kerala"),
            entry("33", "Tamil Nadu"),
            entry("34", "Puducherry"),
            entry("35", "Andaman and Nicobar Islands"),
            entry("36", "Telangana"),
            entry("37", "Andhra Pradesh"),
            entry("38", "Ladakh"),
            entry("97", "Other Territory"));

    private GstStates() {
    }

    public static Optional<String> name(String stateCode) {
        return Optional.ofNullable(NAMES.get(stateCode));
    }
}
