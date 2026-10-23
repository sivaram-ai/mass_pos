package com.masspos.catalog;

import com.masspos.common.Csv;
import com.masspos.web.FieldRejectedException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalogue as a sheet a shopkeeper can open, edit and send back.
 *
 * <p>Money is in rupees and tax in percent, not the paise and basis points the database keeps:
 * nobody types 12550 for a hundred and twenty-five fifty. Columns are found by their heading
 * rather than their position, so a shop may reorder them or keep notes in a column of its own,
 * and only Code, Name and Unit have to be there.
 *
 * <p>A file is taken whole or not at all. Half a catalogue, with the rest refused on row 40, is
 * worse than a clear refusal naming the rows to fix.
 */
public final class ProductCsv {

    static final String CODE = "Code";
    static final String NAME = "Name";
    static final String UNIT = "Unit";
    static final List<String> COLUMNS = List.of(CODE, "Barcode", NAME, "HSN", UNIT,
            "Selling price", "MRP", "Price includes tax", "GST %", "Cess %", "Sell without stock");
    /** Enough to fix a file in one pass without burying the message. */
    private static final int MAX_PROBLEMS = 10;

    private ProductCsv() {
    }

    /** The catalogue from the entities, for a backup that has no screen to ask about stock. */
    public static String writeCatalogue(List<Product> products) {
        return write(products.stream().map(product -> ProductController.ProductView.of(product, 0)).toList());
    }

    public static String write(List<ProductController.ProductView> products) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(COLUMNS);
        for (ProductController.ProductView product : products) {
            rows.add(List.of(product.sku(),
                    product.barcode() == null ? "" : product.barcode(),
                    product.name(),
                    product.hsnCode(),
                    product.unit().name(),
                    rupees(product.sellingPricePaise()),
                    rupees(product.mrpPaise()),
                    yesNo(product.taxInclusive()),
                    percent(product.gstRateBp()),
                    percent(product.cessRateBp()),
                    yesNo(product.billWithoutStock())));
        }
        return Csv.write(rows);
    }

    public static List<ProductController.SaveProductRequest> read(String csv) {
        List<List<String>> rows = Csv.parse(csv);
        if (rows.isEmpty()) {
            throw refuse(List.of("is empty. Start from one the Extract products button made"));
        }
        Map<String, Integer> columns = headings(rows.get(0));
        for (String required : List.of(CODE, NAME, UNIT)) {
            if (!columns.containsKey(required.toLowerCase(Locale.ROOT))) {
                throw refuse(List.of("must start with a row naming the columns, among them "
                        + String.join(", ", COLUMNS) + ". The " + required + " column is missing"));
            }
        }

        List<String> problems = new ArrayList<>();
        List<ProductController.SaveProductRequest> items = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            int line = index + 1;
            Row row = new Row(rows.get(index), columns, line, problems);
            items.add(new ProductController.SaveProductRequest(
                    row.text(CODE), row.text("Barcode"), row.text(NAME), row.text("HSN"),
                    row.unit(), row.paise("Selling price"), row.paise("MRP"),
                    row.yesNo("Price includes tax"), row.basisPoints("GST %"),
                    row.basisPoints("Cess %"), row.yesNo("Sell without stock")));
        }
        if (!problems.isEmpty()) {
            throw refuse(problems);
        }
        return items;
    }

    /**
     * Reads after the field label the screen puts in front of it, so the shop sees
     * "File was not loaded: row 3 has a Unit of ...". No full stop: the handler adds one.
     */
    static FieldRejectedException refuse(List<String> problems) {
        if (problems.size() == 1 && !problems.get(0).startsWith("row ")) {
            return new FieldRejectedException("file", problems.get(0));
        }
        List<String> shown = problems.size() <= MAX_PROBLEMS ? problems : problems.subList(0, MAX_PROBLEMS);
        String more = problems.size() <= MAX_PROBLEMS ? ""
                : "; and " + (problems.size() - MAX_PROBLEMS) + " more";
        return new FieldRejectedException("file", "was not loaded: " + String.join("; ", shown) + more);
    }

    private static Map<String, Integer> headings(List<String> first) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int at = 0; at < first.size(); at++) {
            found.putIfAbsent(first.get(at).trim().toLowerCase(Locale.ROOT), at);
        }
        return found;
    }

    private static String rupees(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }

    private static String percent(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString();
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    /** One line of the sheet, which reports what it cannot read rather than throwing on the spot. */
    private record Row(List<String> cells, Map<String, Integer> columns, int line, List<String> problems) {

        String text(String column) {
            Integer at = columns.get(column.toLowerCase(Locale.ROOT));
            return at == null || at >= cells.size() ? "" : cells.get(at).trim();
        }

        UnitOfMeasure unit() {
            String value = text(UNIT);
            try {
                return UnitOfMeasure.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                problems.add("row " + line + " has a Unit of \"" + value + "\", which is not one of "
                        + String.join(", ", List.of("NOS", "PCS", "BOX", "PAC", "DOZ", "KGS", "GMS", "LTR", "MLT", "MTR")));
                return UnitOfMeasure.NOS;
            }
        }

        long paise(String column) {
            return scaled(column, 2);
        }

        int basisPoints(String column) {
            return (int) scaled(column, 2);
        }

        private long scaled(String column, int places) {
            String value = text(column).replace(",", "");
            if (value.isEmpty()) {
                return 0;
            }
            try {
                return new BigDecimal(value).movePointRight(places).longValueExact();
            } catch (ArithmeticException tooPrecise) {
                problems.add("row " + line + " has a " + column + " of \"" + value
                        + "\", which has more decimal places than a till can hold");
            } catch (NumberFormatException notANumber) {
                problems.add("row " + line + " has a " + column + " of \"" + value + "\", which is not a number");
            }
            return 0;
        }

        boolean yesNo(String column) {
            String value = text(column).toLowerCase(Locale.ROOT);
            if (value.isEmpty() || List.of("no", "n", "false", "0").contains(value)) {
                return false;
            }
            if (List.of("yes", "y", "true", "1").contains(value)) {
                return true;
            }
            problems.add("row " + line + " has a " + column + " of \"" + text(column)
                    + "\", which should be Yes or No");
            return false;
        }
    }
}