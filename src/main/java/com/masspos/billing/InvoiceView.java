package com.masspos.billing;

import com.masspos.catalog.UnitOfMeasure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An invoice as the screens show it.
 *
 * @param taxInvoice false for the bill of a shop without a GSTIN, which carries no tax
 */
public record InvoiceView(UUID id, String invoiceNumber, LocalDate invoiceDate, Instant issuedAt, InvoiceStatus status,
                          boolean taxInvoice, String terminalCode, String cashier, String buyerName, String buyerGstin,
                          String placeOfSupply, long taxableValuePaise, long cgstPaise, long sgstPaise,
                          long igstPaise, long cessPaise, long roundOffPaise, long grandTotalPaise,
                          long changePaise, String cancelReason, List<Line> lines, List<Payment> payments) {

    public record Line(int lineNo, UUID productId, String sku, String name, String hsnCode, UnitOfMeasure unit,
                       long quantityMilli, long unitPricePaise, long discountPaise, int gstRateBp,
                       long taxableValuePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise,
                       long lineTotalPaise) {
    }

    public record Payment(PaymentMode mode, long amountPaise, long tenderedPaise, String reference) {
    }

    public static InvoiceView of(Invoice invoice) {
        List<Line> lines = invoice.getItems().stream()
                .map(item -> new Line(item.getLineNo(), item.getProduct().getId(), item.getSku(),
                        item.getProductName(), item.getHsnCode(), item.getUnit(), item.getQuantityMilli(),
                        item.getUnitPricePaise(), item.getDiscountPaise(), item.getGstRateBp(),
                        item.getTaxableValuePaise(), item.getCgstPaise(), item.getSgstPaise(), item.getIgstPaise(),
                        item.getCessPaise(), item.getLineTotalPaise()))
                .toList();
        List<Payment> payments = invoice.getPayments().stream()
                .map(payment -> new Payment(payment.getMode(), payment.getAmountPaise(), payment.getTenderedPaise(),
                        payment.getReference()))
                .toList();
        long change = payments.stream().mapToLong(p -> p.tenderedPaise() - p.amountPaise()).sum();
        return new InvoiceView(invoice.getId(), invoice.getInvoiceNumber(), invoice.getInvoiceDate(),
                invoice.getIssuedAt(), invoice.getStatus(), invoice.isTaxInvoice(), invoice.getTerminalCode(),
                invoice.getCashier().getDisplayName(), invoice.getBuyerName(), invoice.getBuyerGstin(),
                invoice.getPlaceOfSupply(), invoice.getTaxableValuePaise(), invoice.getCgstPaise(),
                invoice.getSgstPaise(), invoice.getIgstPaise(), invoice.getCessPaise(), invoice.getRoundOffPaise(),
                invoice.getGrandTotalPaise(), change, invoice.getCancelReason(), lines, payments);
    }
}
