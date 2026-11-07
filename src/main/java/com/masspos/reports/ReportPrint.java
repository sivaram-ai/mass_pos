package com.masspos.reports;

import com.masspos.common.IndiaTime;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.PrinterProperties;
import com.masspos.settings.ShopSettings;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The day's takings on paper, for the manager who closes the shop with the drawer in front of them
 * and wants the figures beside the cash rather than on a screen in the back office.
 *
 * <p>It is laid out like a receipt because it comes out of the receipt printer: one column of
 * labels and figures, the items as a short table sized to the paper.
 */
public final class ReportPrint {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** Which part of what sold goes on the paper. */
    public enum Items {
        /** Every item that sold, however long the list. */
        ALL,
        /** The ten the screen calls the best sellers, for a short printout. */
        BEST_SELLERS
    }

    private ReportPrint() {
    }

    public static EscPos format(Reports.DayReport report, ShopSettings shop, PrinterProperties paper, Items which) {
        EscPos doc = new EscPos(paper.codePage(), paper.columns());
        String name = shop.displayName();

        doc.align(EscPos.Align.CENTER)
                .size(true, true).bold(true).line("DAY'S TAKINGS").size(false, false).bold(false)
                .wrapped(name.isEmpty() ? "GO RockIt" : name)
                .line(MOMENT.format(at(report.from())) + " to")
                .line(MOMENT.format(at(report.to())))
                .align(EscPos.Align.LEFT)
                .separator('=');

        doc.leftRight("Bills", String.valueOf(report.bills()));
        if (report.cancelledBills() > 0) {
            doc.leftRight("Cancelled", String.valueOf(report.cancelledBills()));
        }
        if (report.returns() > 0) {
            doc.leftRight("Returns", String.valueOf(report.returns()));
            doc.leftRight("Sales", rupees(report.salesTotals().grandTotalPaise()));
            doc.leftRight("Less returns", "-" + rupees(report.returnTotals().grandTotalPaise()));
        }
        doc.leftRight("Taxable value", rupees(report.totals().taxableValuePaise()));
        doc.leftRight("CGST", rupees(report.totals().cgstPaise()));
        doc.leftRight("SGST", rupees(report.totals().sgstPaise()));
        if (report.totals().igstPaise() != 0) {
            doc.leftRight("IGST", rupees(report.totals().igstPaise()));
        }
        doc.leftRight("Round off", rupees(report.totals().roundOffPaise()));
        doc.separator('-')
                .bold(true).leftRight("TOTAL", rupees(report.totals().grandTotalPaise())).bold(false);

        if (!report.payments().isEmpty()) {
            doc.separator('-').bold(true).line("HOW IT WAS PAID").bold(false);
            report.payments().forEach(row ->
                    doc.leftRight(row.mode().name() + " (" + row.bills() + ")", rupees(row.amountPaise())));
        }
        if (!report.cashiers().isEmpty()) {
            doc.separator('-').bold(true).line("BY CASHIER").bold(false);
            report.cashiers().forEach(row ->
                    doc.leftRight(row.name() + " (" + row.bills() + ")", rupees(row.amountPaise())));
        }
        if (!report.terminals().isEmpty()) {
            doc.separator('-').bold(true).line("BY TILL").bold(false);
            report.terminals().forEach(row ->
                    doc.leftRight(row.name() + " (" + row.bills() + ")", rupees(row.amountPaise())));
        }

        List<Reports.ProductRow> sold = which == Items.ALL ? report.soldItems() : report.bestSellers();
        doc.separator('-')
                .bold(true)
                .line(which == Items.ALL ? "ALL ITEMS SOLD" : "BEST SELLERS")
                .bold(false);
        if (sold.isEmpty()) {
            doc.line("Nothing sold");
        } else {
            items(doc, sold, paper.columns());
            if (which == Items.BEST_SELLERS && report.soldItems().size() > sold.size()) {
                doc.line("(top %d of %d items)".formatted(sold.size(), report.soldItems().size()));
            }
        }

        return doc.separator('=').align(EscPos.Align.CENTER).line("End of report").cut();
    }

    /**
     * Item, how many, and what it came to, each on one line and sized to the widest figure on this
     * report so the columns line up. A name too long for the room left is cut, not wrapped.
     */
    private static void items(EscPos doc, List<Reports.ProductRow> sold, int columns) {
        int qty = "QTY".length();
        int amount = "AMOUNT".length();
        for (Reports.ProductRow row : sold) {
            qty = Math.max(qty, quantity(row.quantityMilli()).length());
            amount = Math.max(amount, rupees(row.amountPaise()).length());
        }
        int item = Math.max(8, columns - qty - amount - 2);

        doc.line(pad("ITEM", item) + " " + padLeft("QTY", qty) + " " + padLeft("AMOUNT", amount));
        for (Reports.ProductRow row : sold) {
            doc.line(pad(row.name(), item) + " " + padLeft(quantity(row.quantityMilli()), qty)
                    + " " + padLeft(rupees(row.amountPaise()), amount));
        }
    }

    private static String pad(String text, int width) {
        return text.length() > width ? text.substring(0, width) : text + " ".repeat(width - text.length());
    }

    private static String padLeft(String text, int width) {
        return text.length() > width ? text.substring(0, width) : " ".repeat(width - text.length()) + text;
    }

    private static LocalDateTime at(Instant moment) {
        return LocalDateTime.ofInstant(moment, IndiaTime.ZONE);
    }

    private static String rupees(long paise) {
        long abs = Math.abs(paise);
        return (paise < 0 ? "-" : "") + abs / 100 + "." + "%02d".formatted(abs % 100);
    }

    private static String quantity(long milliUnits) {
        return BigDecimal.valueOf(milliUnits, 3).stripTrailingZeros().toPlainString();
    }
}
