package com.masspos.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a request field path into the words the screen uses for it, so a refusal reads
 * "Address line 2 must be at most 100 characters" rather than "address[1]: size must be between 0 and 100".
 */
final class FieldLabels {

    private static final Map<String, String> KNOWN = Map.ofEntries(
            Map.entry("gstin", "GSTIN"),
            Map.entry("buyerGstin", "Customer GSTIN"),
            Map.entry("buyerName", "Customer name"),
            Map.entry("cin", "CIN"),
            Map.entry("fssaiLicense", "FSSAI licence"),
            Map.entry("upiVpa", "UPI ID"),
            Map.entry("hsnCode", "HSN/SAC code"),
            Map.entry("sku", "Item code"),
            Map.entry("tradeName", "Shop name"),
            Map.entry("address", "Address line"),
            Map.entry("receiptFooter", "Receipt footer line"),
            Map.entry("mrpPaise", "MRP"),
            Map.entry("gstRateBp", "GST rate"),
            Map.entry("cessRateBp", "Cess rate"),
            Map.entry("placeOfSupply", "Place of supply"),
            Map.entry("lines", "Line"),
            Map.entry("payments", "Payment"),
            Map.entry("newPin", "New PIN"),
            Map.entry("currentPin", "Current PIN"),
            Map.entry("pin", "PIN"));

    /** A path segment such as {@code lines[0]} or {@code quantityMilli}. */
    private static final Pattern SEGMENT = Pattern.compile("([A-Za-z0-9_]+)(?:\\[(\\d+)])?");
    private static final Pattern UNIT_SUFFIX = Pattern.compile("(Paise|Milli|Bp)$");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private FieldLabels() {
    }

    static String of(String path) {
        List<String> words = new ArrayList<>();
        for (String part : path.split("\\.")) {
            Matcher segment = SEGMENT.matcher(part);
            if (!segment.matches()) {
                words.add(part);
                continue;
            }
            String label = KNOWN.getOrDefault(segment.group(1), words(segment.group(1)));
            if (segment.group(2) != null) {
                label += " " + (Integer.parseInt(segment.group(2)) + 1);
            }
            words.add(label);
        }
        String joined = String.join(" ", words);
        return joined.isEmpty() ? "This field" : Character.toUpperCase(joined.charAt(0)) + joined.substring(1);
    }

    /** {@code sellingPricePaise} → "selling price". Money and quantity units are storage detail. */
    private static String words(String name) {
        String bare = UNIT_SUFFIX.matcher(name).replaceAll("");
        return WORD_BOUNDARY.matcher(bare.isEmpty() ? name : bare).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
