package com.masspos.catalog;

import com.masspos.common.IndiaTime;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.PrinterProperties;
import com.masspos.settings.ShopSettings;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * The catalogue as a printed list: one line an item, in code order, with the code, the name and
 * the rate, and {@code (N)} after the rate of an item that is no longer sold.
 *
 * <p>Everything about an item is on its own line, so the list can be read down a column rather
 * than hunted through. A name too long for the room left is cut rather than carried onto a second
 * line, which is the trade the one-line-an-item rule asks for.
 */
public final class PriceList {

    private static final DateTimeFormatter PRINTED_AT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** What marks an item the shop has stopped selling. */
    static final String STOPPED = "(N)";

    private static final String CODE_HEADING = "CODE";
    private static final String ITEM_HEADING = "ITEM";
    private static final String RATE_HEADING = "RATE";
    /** Between the code and the name, so neither runs into the other. */
    private static final int GAP = 2;
    /** Below this a name says nothing, so the code column gives way instead. */
    private static final int MIN_ITEM_COLUMN = 8;

    private PriceList() {
    }

    /**
     * Code order as the Products screen shows it: a run of digits compares as a number, so 2 comes
     * before 10 and PG-9 before PG-100. Plain string order puts "10" ahead of "2", and a printout
     * ordered differently from the screen it was printed from reads as a bug.
     */
    static final Comparator<Product> BY_CODE = Comparator.comparing(Product::getSku, PriceList::naturally);

    public static EscPos format(List<Product> products, ShopSettings shop, PrinterProperties printer) {
        return format(products, shop, printer, LocalDateTime.now(IndiaTime.ZONE));
    }

    static EscPos format(List<Product> products, ShopSettings shop, PrinterProperties paper, LocalDateTime printedAt) {
        List<Product> ordered = products.stream().sorted(BY_CODE).toList();
        EscPos doc = new EscPos(paper.codePage(), paper.columns());
        Columns layout = Columns.of(ordered, paper.columns());

        String name = shop.displayName();
        doc.align(EscPos.Align.CENTER)
                .size(true, true).bold(true).line("PRICE LIST").size(false, false).bold(false)
                .wrapped(name.isEmpty() ? "GO RockIt" : name)
                .line(PRINTED_AT.format(printedAt))
                .align(EscPos.Align.LEFT)
                .separator('=')
                .line(layout.row(CODE_HEADING, ITEM_HEADING, RATE_HEADING, false))
                .separator('-');

        for (Product product : ordered) {
            doc.line(layout.row(product.getSku(), product.getName(), rupees(product.getSellingPricePaise()),
                    !product.isActive()));
        }

        long stopped = ordered.stream().filter(product -> !product.isActive()).count();
        doc.separator('-')
                .line(ordered.size() + (ordered.size() == 1 ? " item" : " items")
                        + (stopped == 0 ? "" : ", " + stopped + " not sold"));
        if (stopped > 0) {
            doc.line(STOPPED + " is an item the shop has stopped selling");
        }
        return doc.cut();
    }

    /**
     * How wide each of the three columns is on this paper, worked out from the longest code and
     * the dearest rate on the list so the figures line up and the name gets whatever is left.
     */
    private record Columns(int code, int item, int rate) {

        static Columns of(List<Product> products, int paperColumns) {
            int code = CODE_HEADING.length();
            int rate = RATE_HEADING.length();
            boolean anyStopped = false;
            for (Product product : products) {
                code = Math.max(code, product.getSku().length());
                rate = Math.max(rate, rupees(product.getSellingPricePaise()).length());
                anyStopped |= !product.isActive();
            }
            // The mark hangs off the right of the rate, so it costs room only when something needs it.
            int mark = anyStopped ? STOPPED.length() + 1 : 0;
            int item = paperColumns - code - GAP - 1 - rate - mark;
            if (item < MIN_ITEM_COLUMN) {
                // Narrow paper: take it out of the code, which is short, before the name, which is not.
                code = Math.max(CODE_HEADING.length(), code - (MIN_ITEM_COLUMN - item));
                item = Math.max(1, paperColumns - code - GAP - 1 - rate - mark);
            }
            return new Columns(code, item, rate);
        }

        /** {@code 10  Tea 250g            120.00 (N)} */
        String row(String code, String name, String rate, boolean stopped) {
            StringBuilder line = new StringBuilder()
                    .append(fit(code, this.code))
                    .append(" ".repeat(GAP))
                    .append(fit(name, item))
                    .append(' ')
                    .append(" ".repeat(Math.max(0, this.rate - rate.length())))
                    .append(rate);
            if (stopped) {
                line.append(' ').append(STOPPED);
            }
            return line.toString();
        }

        /** Padded to the column, or cut to it: the line must not wrap onto a second one. */
        private static String fit(String text, int width) {
            return text.length() > width ? text.substring(0, width)
                    : text + " ".repeat(width - text.length());
        }
    }

    /** 20000 -> "200.00". The receipt's own wording for money, which this list sits beside. */
    private static String rupees(long paise) {
        long abs = Math.abs(paise);
        return (paise < 0 ? "-" : "") + abs / 100 + "." + "%02d".formatted(abs % 100);
    }

    private static int naturally(String left, String right) {
        int at = 0;
        int to = 0;
        while (at < left.length() && to < right.length()) {
            char here = left.charAt(at);
            char there = right.charAt(to);
            if (Character.isDigit(here) && Character.isDigit(there)) {
                int fromLeft = at;
                int fromRight = to;
                while (at < left.length() && Character.isDigit(left.charAt(at))) {
                    at++;
                }
                while (to < right.length() && Character.isDigit(right.charAt(to))) {
                    to++;
                }
                int compared = compareNumbers(left.substring(fromLeft, at), right.substring(fromRight, to));
                if (compared != 0) {
                    return compared;
                }
            } else {
                int compared = Character.compare(Character.toLowerCase(here), Character.toLowerCase(there));
                if (compared != 0) {
                    return compared;
                }
                at++;
                to++;
            }
        }
        return (left.length() - at) - (right.length() - to);
    }

    /** Compared as numbers without parsing them: a code could hold more digits than a long. */
    private static int compareNumbers(String left, String right) {
        String here = left.replaceFirst("^0+(?=.)", "");
        String there = right.replaceFirst("^0+(?=.)", "");
        return here.length() != there.length() ? here.length() - there.length() : here.compareTo(there);
    }
}
