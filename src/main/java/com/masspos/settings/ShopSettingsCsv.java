package com.masspos.settings;

import com.masspos.common.Csv;
import com.masspos.web.FieldRejectedException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shop's own details as a two-column sheet: the setting down the left, its value on the right.
 *
 * <p>Sixteen settings across sixteen columns would run off the side of the screen; down the page
 * they read like a form, which is what they are. Address and footer lines are numbered, and a shop
 * may have as many of each as it needs.
 *
 * <p>Reading a file fills the settings screen and saves nothing. Whoever loaded it sees what
 * arrived, and the usual checks - the GSTIN check character, the CIN and FSSAI formats - happen
 * when they press Save, in the same place they happen when the details are typed by hand.
 */
public final class ShopSettingsCsv {

    private static final String LEGAL_NAME = "Legal name";
    private static final String TRADE_NAME = "Trade name";
    private static final String GSTIN = "GSTIN";
    private static final String ADDRESS = "Address";
    private static final String PHONE = "Phone";
    private static final String EMAIL = "Email";
    private static final String WEBSITE = "Website";
    private static final String CIN = "CIN";
    private static final String FSSAI = "FSSAI licence";
    private static final String UPI = "UPI ID";
    private static final String FOOTER = "Receipt footer";
    private static final String SERVICE_NAME = "Service provider";
    private static final String SERVICE_PHONE = "Service phone";
    private static final String SERVICE_EMAIL = "Service email";
    private static final String ROUND_TOTAL = "Round invoice total";
    private static final String BLOCK_STOCK = "Block negative stock";

    /** Everything but the numbered lines, which are written out as many as the shop has. */
    private static final List<String> SINGLE_FIELDS = List.of(LEGAL_NAME, TRADE_NAME, GSTIN, PHONE, EMAIL,
            WEBSITE, CIN, FSSAI, UPI, SERVICE_NAME, SERVICE_PHONE, SERVICE_EMAIL, ROUND_TOTAL, BLOCK_STOCK);

    private ShopSettingsCsv() {
    }

    public static String write(ShopSettingsForm shop) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Setting", "Value"));
        rows.add(List.of(LEGAL_NAME, text(shop.legalName())));
        rows.add(List.of(TRADE_NAME, text(shop.tradeName())));
        rows.add(List.of(GSTIN, text(shop.gstin())));
        numbered(rows, ADDRESS, shop.address());
        rows.add(List.of(PHONE, text(shop.phone())));
        rows.add(List.of(EMAIL, text(shop.email())));
        rows.add(List.of(WEBSITE, text(shop.website())));
        rows.add(List.of(CIN, text(shop.cin())));
        rows.add(List.of(FSSAI, text(shop.fssaiLicense())));
        rows.add(List.of(UPI, text(shop.upiVpa())));
        numbered(rows, FOOTER, shop.receiptFooter());
        rows.add(List.of(SERVICE_NAME, text(shop.serviceProviderName())));
        rows.add(List.of(SERVICE_PHONE, text(shop.servicePhone())));
        rows.add(List.of(SERVICE_EMAIL, text(shop.serviceEmail())));
        rows.add(List.of(ROUND_TOTAL, yesNo(shop.roundInvoiceTotal())));
        rows.add(List.of(BLOCK_STOCK, yesNo(shop.blockNegativeStock())));
        return Csv.write(rows);
    }

    public static ShopSettingsForm read(String csv) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> address = new ArrayList<>();
        List<String> footer = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (List<String> row : Csv.parse(csv)) {
            String name = row.isEmpty() ? "" : row.get(0).trim();
            String value = row.size() < 2 ? "" : row.get(1).trim();
            if (name.isEmpty() || name.equalsIgnoreCase("Setting")) {
                continue;
            }
            if (startsWith(name, FOOTER)) {
                footer.add(value);
            } else if (startsWith(name, ADDRESS)) {
                address.add(value);
            } else if (SINGLE_FIELDS.stream().anyMatch(field -> field.equalsIgnoreCase(name))) {
                values.put(key(name), value);
            } else {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new FieldRejectedException("file", "was not loaded: these are not settings this till"
                    + " knows - " + String.join(", ", unknown) + ". Check them against a file the Extract"
                    + " company details button made");
        }
        if (values.isEmpty() && address.isEmpty() && footer.isEmpty()) {
            throw new FieldRejectedException("file", "holds none of the shop's details. Start from one"
                    + " the Extract company details button made");
        }
        address.removeIf(String::isBlank);
        footer.removeIf(String::isBlank);

        return new ShopSettingsForm(values.get(key(LEGAL_NAME)), values.get(key(TRADE_NAME)),
                values.get(key(GSTIN)), address, values.get(key(PHONE)), values.get(key(EMAIL)),
                values.get(key(WEBSITE)), values.get(key(CIN)), values.get(key(FSSAI)), values.get(key(UPI)),
                footer, values.get(key(SERVICE_NAME)), values.get(key(SERVICE_PHONE)),
                values.get(key(SERVICE_EMAIL)), isYes(values.get(key(ROUND_TOTAL))),
                isYes(values.get(key(BLOCK_STOCK))));
    }

    /** One row per line the shop has, and one empty row when it has none, so the heading is there to fill in. */
    private static void numbered(List<List<String>> rows, String heading, List<String> lines) {
        List<String> present = lines == null ? List.of() : lines;
        for (int line = 0; line < Math.max(present.size(), 1); line++) {
            rows.add(List.of(heading + " " + (line + 1), line < present.size() ? text(present.get(line)) : ""));
        }
    }

    private static boolean startsWith(String name, String heading) {
        return key(name).startsWith(key(heading));
    }

    private static String key(String field) {
        return field.toLowerCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private static boolean isYes(String value) {
        return value != null && List.of("yes", "y", "true", "1").contains(value.trim().toLowerCase(Locale.ROOT));
    }
}