package com.masspos.receipt;

import com.masspos.billing.GstStates;
import com.masspos.billing.Invoice;
import com.masspos.billing.InvoiceItem;
import com.masspos.billing.InvoiceStatus;
import com.masspos.billing.SellerDetails;
import com.masspos.common.IndiaTime;
import com.masspos.hardware.EscPos;
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
    private static final int RATE_COLUMN_WIDTH = 6;

    private final PrinterProperties printer;

    public ReceiptFormatter(PrinterProperties printer) {
        this.printer = printer;
    }

    /** @param openDrawer kick the drawer first, so it opens while the receipt prints */
    public EscPos format(Invoice invoice, ShopSettings shop, ReceiptCopy copy, boolean openDrawer) {
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

    private void header(EscPos doc, Invoice invoice, ShopSettings shop, ReceiptCopy copy) {
        SellerDetails seller = invoice.getSeller();
        doc.align(CENTER).line(copy.label());
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
        doc.bold(true).line(invoice.isTaxInvoice() ? "TAX INVOICE" : "BILL").bold(false);
        if (isCancelled(invoice)) {
            doc.size(false, true).bold(true).line("*** CANCELLED ***").size(false, false).bold(false);
        }
        doc.align(LEFT).separator('-');
        doc.leftRight((invoice.isTaxInvoice() ? "Invoice: " : "Bill No: ") + invoice.getInvoiceNumber(),
                "Date: " + DATE.format(invoice.getInvoiceDate()));
        doc.leftRight("Cashier: " + invoice.getCashier().getDisplayName(),
                "Time: " + TIME.format(invoice.getIssuedAt().atZone(IndiaTime.ZONE)));
        if (invoice.isB2b()) {
            if (hasText(invoice.getBuyerName())) {
                doc.wrapped("Bill to: " + invoice.getBuyerName());
            }
            doc.line("Buyer GSTIN: " + invoice.getBuyerGstin());
        }
        doc.separator('-');
    }

    private void items(EscPos doc, Invoice invoice) {
        doc.bold(true).leftRight("Item", "Amount").bold(false);
        for (InvoiceItem item : invoice.getItems()) {
            doc.wrapped(item.getLineNo() + ". " + item.getProductName());
            String quantity = Amounts.quantity(item.getQuantityMilli())
                    + (item.getUnit().isWholeUnitsOnly() ? "" : " " + item.getUnit());
            StringBuilder detail = new StringBuilder("  ");
            if (hasText(item.getHsnCode())) {
                detail.append(" HSN ").append(item.getHsnCode());
            }
            if (invoice.isTaxInvoice()) {
                detail.append(' ').append(Amounts.percent(item.getGstRateBp())).append(' ');
            }
            detail.append(' ').append(quantity).append(" x ").append(Amounts.rupees(item.getUnitPricePaise()));
            doc.leftRight(detail.toString(), Amounts.rupees(item.getLineTotalPaise()));
            if (item.getDiscountPaise() > 0) {
                doc.leftRight("   Discount", "-" + Amounts.rupees(item.getDiscountPaise()));
            }
        }
        doc.separator('-');
    }

    private void totals(EscPos doc, Invoice invoice) {
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
                .leftRight("TOTAL Rs.", Amounts.rupees(invoice.getGrandTotalPaise()))
                .size(false, false).bold(false);
        invoice.getPayments().forEach(payment -> {
            doc.leftRight("  " + payment.getMode(), Amounts.rupees(payment.getAmountPaise()));
            long change = payment.getTenderedPaise() - payment.getAmountPaise();
            if (change > 0) {
                doc.leftRight("  Tendered", Amounts.rupees(payment.getTenderedPaise()));
                doc.leftRight("  Change", Amounts.rupees(change));
            }
        });
        doc.separator('-');
    }

    /** Rate-wise tax breakdown, as Rule 46 wants the rate and amount of each tax. */
    private void gstSummary(EscPos doc, Invoice invoice) {
        boolean interState = invoice.isInterState();
        boolean cess = invoice.getCessPaise() > 0;
        List<String> headers = new ArrayList<>(List.of("GST%", "Taxable"));
        headers.addAll(interState ? List.of("IGST") : List.of("CGST", "SGST"));
        if (cess) {
            headers.add("Cess");
        }

        // rate -> {taxable, cgst, sgst, igst, cess}
        Map<Integer, long[]> byRate = new TreeMap<>();
        for (InvoiceItem item : invoice.getItems()) {
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

    private void footer(EscPos doc, Invoice invoice, ShopSettings shop) {
        if (invoice.isTaxInvoice()) {
            String placeOfSupply = invoice.getPlaceOfSupply();
            doc.wrapped("Place of supply: " + placeOfSupply
                    + GstStates.name(placeOfSupply).map(n -> "-" + n).orElse(""));
            // Retail counter sales are never reverse-charge supplies, but Rule 46 wants it stated.
            doc.line("Reverse charge: No");
        }
        if (isCancelled(invoice)) {
            doc.wrapped("Cancelled: " + invoice.getCancelReason());
        }
        if (invoice.isB2b()) {
            doc.feed(2).align(RIGHT)
                    .wrapped("For " + invoice.getSeller().getLegalName())
                    .line("Authorised Signatory")
                    .align(LEFT);
        }
        if (!isCancelled(invoice) && !shop.getUpiVpa().isEmpty()) {
            doc.align(CENTER).line("Scan to pay with any UPI app")
                    .qrCode(upiUri(invoice, shop), QR_MODULE_DOTS).align(LEFT);
        }
        if (!shop.receiptFooter().isEmpty()) {
            doc.align(CENTER);
            shop.receiptFooter().forEach(doc::wrapped);
            doc.align(LEFT);
        }
    }

    private static String upiUri(Invoice invoice, ShopSettings shop) {
        return "upi://pay?pa=%s&pn=%s&am=%s&cu=INR&tn=%s".formatted(shop.getUpiVpa(),
                urlEncode(invoice.getSeller().getTradeName()), Amounts.rupees(invoice.getGrandTotalPaise()),
                urlEncode(invoice.getInvoiceNumber()));
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

    private static boolean isCancelled(Invoice invoice) {
        return invoice.getStatus() == InvoiceStatus.CANCELLED;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
