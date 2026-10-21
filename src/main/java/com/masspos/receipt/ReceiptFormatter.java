package com.masspos.receipt;

import com.masspos.billing.CreditNote;
import com.masspos.billing.GstStates;
import com.masspos.billing.Invoice;
import com.masspos.billing.SellerDetails;
import com.masspos.billing.TaxDocument;
import com.masspos.billing.TaxLine;
import com.masspos.common.IndiaTime;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.ParcelQr;
import com.masspos.hardware.PrinterProperties;
import com.masspos.settings.ShopSettings;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.masspos.hardware.EscPos.Align.CENTER;
import static com.masspos.hardware.EscPos.Align.LEFT;
import static com.masspos.hardware.EscPos.Align.RIGHT;

/**
 * Lays out a GST tax invoice (Rule 46) on thermal paper: supplier name, address, GSTIN (plus CIN
 * and FSSAI number when registered); invoice number and date; buyer GSTIN for B2B; per line HSN
 * (when the item has one), quantity, rate and value; CGST + SGST or IGST per rate; place of supply
 * with state name; reverse-charge statement; copy marking.
 *
 * <p>A shop without a GSTIN gets a plain "BILL" instead: no GSTIN, tax lines, rate summary, place
 * of supply or reverse-charge statement, since it charges no tax.
 *
 * <p>A credit note (return) prints the same way, titled "CREDIT NOTE" (or "RETURN" without a GSTIN),
 * with the bill it answers, the reason, and the refund in place of the payments.
 *
 * <p>Legal identity comes from the invoice's own seller snapshot, never from current settings, so a
 * reprint years later still matches the original. Only the contact lines, UPI ID and footer are
 * taken from settings as they are at print time.
 *
 * <p>Pure: reads the invoice (call inside a transaction, as items and cashier load lazily) and
 * returns bytes. Printing happens later, outside the transaction.
 */
@Component
public class ReceiptFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final int QR_MODULE_DOTS = 6;
    /** How much of the paper's width the UPI code takes, leaving room for the parcel mark. */
    private static final int CODE_SHARE_PERCENT = 45;
    private static final int RATE_COLUMN_WIDTH = 6;
    /** Even with huge figures, a name needs room to be readable. */
    private static final int MIN_ITEM_COLUMN = 10;

    /**
     * @param invoice    an {@link Invoice} or a {@link CreditNote}
     * @param printer    the printer as it is set now: paper width and code page shape the layout
     * @param openDrawer kick the drawer first, so it opens while the receipt prints
     */
    public EscPos format(TaxDocument invoice, ShopSettings shop, PrinterProperties printer, ReceiptCopy copy,
                         boolean openDrawer) {
        EscPos doc = new EscPos(printer.codePage(), printer.columns());
        if (openDrawer) {
            doc.openDrawer(printer.drawerPin(), printer.drawerPulse());
        }
        header(doc, invoice, shop, copy);
        items(doc, invoice);
        totals(doc, invoice);
        if (invoice.isTaxInvoice()) {
            gstSummary(doc, invoice);
        }
        footer(doc, invoice, shop);
        return doc.cut();
    }

    private void header(EscPos doc, TaxDocument invoice, ShopSettings shop, ReceiptCopy copy) {
        SellerDetails seller = invoice.getSeller();
        doc.align(CENTER);
        if (!copy.label().isEmpty()) {
            doc.line(copy.label());
        }
        doc.size(true, true).bold(true).wrapped(seller.getTradeName()).size(false, false).bold(false);
        if (!seller.getTradeName().equals(seller.getLegalName())) {
            doc.wrapped(seller.getLegalName());
        }
        seller.getAddressLines().forEach(doc::wrapped);
        if (!shop.getPhone().isEmpty()) {
            doc.wrapped("Ph: " + shop.getPhone());
        }
        if (!shop.getEmail().isEmpty()) {
            doc.wrapped(shop.getEmail());
        }
        if (!shop.getWebsite().isEmpty()) {
            doc.wrapped(shop.getWebsite());
        }
        if (invoice.isTaxInvoice()) {
            doc.line("GSTIN: " + seller.getGstin());
        }
        if (seller.getCin() != null) {
            doc.line("CIN: " + seller.getCin());
        }
        if (seller.getFssaiLicense() != null) {
            doc.line("FSSAI Lic. No: " + seller.getFssaiLicense());
        }
        // A shop without a GSTIN cannot issue a tax invoice; its receipt is a plain bill.
        doc.bold(true).line(title(invoice)).bold(false);
        if (isCancelled(invoice)) {
            doc.size(false, true).bold(true).line("*** CANCELLED ***").size(false, false).bold(false);
        }
        doc.align(LEFT).separator('-');
        doc.leftRight(numberLabel(invoice) + invoice.getNumber(), "Date: " + DATE.format(invoice.getDocumentDate()));
        doc.leftRight("Cashier: " + invoice.getCashier().getDisplayName(),
                "Time: " + TIME.format(invoice.getIssuedAt().atZone(IndiaTime.ZONE)));
        if (invoice.isB2b()) {
            if (hasText(invoice.getBuyerName())) {
                doc.wrapped("Bill to: " + invoice.getBuyerName());
            }
            doc.line("Buyer GSTIN: " + invoice.getBuyerGstin());
        }
        if (invoice instanceof Invoice bill && bill.getReplacesInvoiceNumber() != null) {
            doc.wrapped("Replaces bill: " + bill.getReplacesInvoiceNumber());
        }
        if (invoice instanceof CreditNote note) {
            if (note.getOriginalInvoiceNumber() != null) {
                doc.wrapped("Against bill: " + note.getOriginalInvoiceNumber() + " of "
                        + DATE.format(note.getOriginalInvoiceDate()));
            }
            if (hasText(note.getReason())) {
                doc.wrapped("Reason: " + note.getReason());
            }
        }
        doc.separator('-');
    }

    /**
     * Four columns: item, rate, quantity, amount. A name too long for its column carries on
     * underneath itself, so the figures stay in line however long the names are. The tax rate is
     * not repeated per line; Rule 46 wants it rate-wise, which is what the summary below gives.
     */
    private void items(EscPos doc, TaxDocument invoice) {
        int[] widths = itemColumnWidths(doc.lineWidth(), invoice);
        doc.bold(true).line(row(widths, List.of("Item", "Rate", "Qty", "Amount"))).bold(false);
        doc.separator('-');
        for (TaxLine item : invoice.getLines()) {
            List<String> name = EscPos.wrap(item.getLineNo() + ". " + item.getProductName(),
                    Math.max(MIN_ITEM_COLUMN, widths[0] - 1));
            doc.line(row(widths, List.of(name.getFirst(), rateCell(item), quantityCell(item), amountCell(item))));
            name.subList(1, name.size()).forEach(rest -> doc.line("   " + rest));
            if (item.getDiscountPaise() > 0) {
                doc.leftRight("   Discount", "-" + Amounts.rupees(item.getDiscountPaise()));
            }
        }
        doc.separator('-');
    }

    /** The figures get exactly the room they need, and the name column takes what is left. */
    private static int[] itemColumnWidths(int lineWidth, TaxDocument invoice) {
        int rate = "Rate".length();
        int qty = "Qty".length();
        int amount = "Amount".length();
        for (TaxLine item : invoice.getLines()) {
            rate = Math.max(rate, rateCell(item).length());
            qty = Math.max(qty, quantityCell(item).length());
            amount = Math.max(amount, amountCell(item).length());
        }
        // One space in front of each figure column, which row() adds back as padding.
        int figures = rate + 1 + qty + 1 + amount + 1;
        return new int[] {Math.max(MIN_ITEM_COLUMN, lineWidth - figures), rate + 1, qty + 1, amount + 1};
    }

    private static String rateCell(TaxLine item) {
        return Amounts.rupees(item.getUnitPricePaise());
    }

    private static String quantityCell(TaxLine item) {
        return Amounts.quantity(item.getQuantityMilli())
                + (item.getUnit().isWholeUnitsOnly() ? "" : " " + item.getUnit());
    }

    private static String amountCell(TaxLine item) {
        return Amounts.rupees(item.getLineTotalPaise());
    }

    private void totals(EscPos doc, TaxDocument invoice) {
        if (invoice.isTaxInvoice()) {
            doc.leftRight("Taxable value", Amounts.rupees(invoice.getTaxableValuePaise()));
            if (invoice.isInterState()) {
                doc.leftRight("IGST", Amounts.rupees(invoice.getIgstPaise()));
            } else {
                doc.leftRight("CGST", Amounts.rupees(invoice.getCgstPaise()));
                doc.leftRight("SGST", Amounts.rupees(invoice.getSgstPaise()));
            }
            if (invoice.getCessPaise() > 0) {
                doc.leftRight("Cess", Amounts.rupees(invoice.getCessPaise()));
            }
        }
        if (invoice.getRoundOffPaise() != 0) {
            doc.leftRight("Round off", Amounts.rupees(invoice.getRoundOffPaise()));
        }
        doc.size(false, true).bold(true)
                .leftRight(invoice instanceof CreditNote ? "REFUND Rs." : "TOTAL Rs.",
                        Amounts.rupees(invoice.getGrandTotalPaise()))
                .size(false, false).bold(false);
        if (invoice instanceof Invoice bill) {
            bill.getPayments().forEach(payment -> {
                doc.leftRight("  " + payment.getMode(), Amounts.rupees(payment.getAmountPaise()));
                long change = payment.getTenderedPaise() - payment.getAmountPaise();
                if (change > 0) {
                    doc.leftRight("  Tendered", Amounts.rupees(payment.getTenderedPaise()));
                    doc.leftRight("  Change", Amounts.rupees(change));
                }
            });
        } else if (invoice instanceof CreditNote note) {
            note.getRefunds().forEach(back ->
                    doc.leftRight("  Paid back by " + back.getMode(), Amounts.rupees(back.getAmountPaise())));
        }
        doc.separator('-');
    }

    /** Rate-wise tax breakdown, as Rule 46 wants the rate and amount of each tax. */
    private void gstSummary(EscPos doc, TaxDocument invoice) {
        boolean interState = invoice.isInterState();
        boolean cess = invoice.getCessPaise() > 0;
        List<String> headers = new ArrayList<>(List.of("GST%", "Taxable"));
        headers.addAll(interState ? List.of("IGST") : List.of("CGST", "SGST"));
        if (cess) {
            headers.add("Cess");
        }

        // rate -> {taxable, cgst, sgst, igst, cess}
        Map<Integer, long[]> byRate = new TreeMap<>();
        for (TaxLine item : invoice.getLines()) {
            long[] sums = byRate.computeIfAbsent(item.getGstRateBp(), rate -> new long[5]);
            sums[0] += item.getTaxableValuePaise();
            sums[1] += item.getCgstPaise();
            sums[2] += item.getSgstPaise();
            sums[3] += item.getIgstPaise();
            sums[4] += item.getCessPaise();
        }

        int[] widths = columnWidths(doc.lineWidth(), headers.size());
        doc.line(row(widths, headers));
        byRate.forEach((rate, sums) -> {
            List<String> cells = new ArrayList<>(List.of(Amounts.percent(rate), Amounts.rupees(sums[0])));
            if (interState) {
                cells.add(Amounts.rupees(sums[3]));
            } else {
                cells.add(Amounts.rupees(sums[1]));
                cells.add(Amounts.rupees(sums[2]));
            }
            if (cess) {
                cells.add(Amounts.rupees(sums[4]));
            }
            doc.line(row(widths, cells));
        });
        doc.separator('-');
    }

    private void footer(EscPos doc, TaxDocument invoice, ShopSettings shop) {
        if (invoice.isTaxInvoice()) {
            String placeOfSupply = invoice.getPlaceOfSupply();
            doc.wrapped("Place of supply: " + placeOfSupply
                    + GstStates.name(placeOfSupply).map(n -> "-" + n).orElse(""));
            // Retail counter sales are never reverse-charge supplies, but Rule 46 wants it stated.
            doc.line("Reverse charge: No");
        }
        if (invoice instanceof Invoice bill && bill.isCancelled()) {
            doc.wrapped("Cancelled: " + bill.getCancelReason());
        }
        if (invoice.isB2b()) {
            doc.feed(2).align(RIGHT)
                    .wrapped("For " + invoice.getSeller().getLegalName())
                    .line("Authorised Signatory")
                    .align(LEFT);
        }
        boolean parcel = invoice instanceof Invoice bill && bill.isParcel();
        // Only an open bill asks for money: never a cancelled bill, and never a refund.
        if (invoice instanceof Invoice bill && !bill.isCancelled() && !shop.getUpiVpa().isEmpty()) {
            String upi = upiUri(invoice, shop);
            doc.align(CENTER).line("Scan to pay with any UPI app");
            if (parcel) {
                // Drawn by the till, because the printer's own QR command leaves no room beside it.
                doc.raster(ParcelQr.compose(upi, codeDots(doc)), upi, true);
            } else {
                doc.qrCode(upi, QR_MODULE_DOTS);
            }
            doc.align(LEFT);
        } else if (parcel) {
            parcelMark(doc);
        }
        if (!shop.receiptFooter().isEmpty()) {
            doc.align(CENTER);
            shop.receiptFooter().forEach(doc::wrapped);
            doc.align(LEFT);
        }
    }

    /** About the paper the code should take up, in printer dots (a Font A column is 12 dots). */
    private static int codeDots(EscPos doc) {
        return doc.columns() * 12 * CODE_SHARE_PERCENT / 100;
    }

    /** A parcel bill with no code to sit beside still has to say so. */
    private void parcelMark(EscPos doc) {
        doc.align(RIGHT).size(true, true).bold(true)
                .line("P")
                .size(false, false).bold(false).align(LEFT);
    }

    private static String upiUri(TaxDocument invoice, ShopSettings shop) {
        return "upi://pay?pa=%s&pn=%s&am=%s&cu=INR&tn=%s".formatted(shop.getUpiVpa(),
                urlEncode(invoice.getSeller().getTradeName()), Amounts.rupees(invoice.getGrandTotalPaise()),
                urlEncode(invoice.getNumber()));
    }

    private static int[] columnWidths(int lineWidth, int columns) {
        int[] widths = new int[columns];
        int rest = (lineWidth - RATE_COLUMN_WIDTH) / (columns - 1);
        for (int i = 1; i < columns; i++) {
            widths[i] = rest;
        }
        widths[0] = lineWidth - rest * (columns - 1);
        return widths;
    }

    /** First cell left-aligned, the rest right-aligned. Numbers are never truncated. */
    private static String row(int[] widths, List<String> cells) {
        StringBuilder row = new StringBuilder(cells.get(0));
        row.append(" ".repeat(Math.max(0, widths[0] - row.length())));
        for (int i = 1; i < cells.size(); i++) {
            String cell = cells.get(i);
            row.append(" ".repeat(Math.max(1, widths[i] - cell.length()))).append(cell);
        }
        return row.toString();
    }

    private static boolean isCancelled(TaxDocument document) {
        return document instanceof Invoice bill && bill.isCancelled();
    }

    private static String title(TaxDocument document) {
        if (document instanceof CreditNote) {
            return document.isTaxInvoice() ? "CREDIT NOTE" : "RETURN";
        }
        return document.isTaxInvoice() ? "TAX INVOICE" : "BILL";
    }

    private static String numberLabel(TaxDocument document) {
        if (document instanceof CreditNote) {
            return document.isTaxInvoice() ? "Credit note: " : "Return No: ";
        }
        return document.isTaxInvoice() ? "Invoice: " : "Bill No: ";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
