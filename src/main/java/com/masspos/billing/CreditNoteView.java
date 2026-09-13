package com.masspos.billing;

import com.masspos.catalog.UnitOfMeasure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A credit note (return) as the screens show it.
 *
 * @param taxInvoice false for a shop without a GSTIN: a plain return slip with no tax
 */
public record CreditNoteView(UUID id, String creditNoteNumber, LocalDate noteDate, Instant issuedAt,
                             boolean taxInvoice, String terminalCode, String cashier, UUID originalInvoiceId,
                             String originalInvoiceNumber, LocalDate originalInvoiceDate, String buyerName,
                             String buyerGstin, String placeOfSupply, String reason, long taxableValuePaise,
                             long cgstPaise, long sgstPaise, long igstPaise, long cessPaise, long roundOffPaise,
                             long grandTotalPaise, List<Line> lines, List<Refund> refunds) {

    public record Line(int lineNo, Integer originalLineNo, UUID productId, String sku, String name, String hsnCode,
                       UnitOfMeasure unit, long quantityMilli, long unitPricePaise, long discountPaise, int gstRateBp,
                       long taxableValuePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise,
                       long lineTotalPaise) {
    }

    public record Refund(PaymentMode mode, long amountPaise, String reference) {
    }

    public static CreditNoteView of(CreditNote note) {
        return new CreditNoteView(note.getId(), note.getCreditNoteNumber(), note.getNoteDate(), note.getIssuedAt(),
                note.isTaxInvoice(), note.getTerminalCode(), note.getCashier().getDisplayName(),
                note.getOriginalInvoice() == null ? null : note.getOriginalInvoice().getId(),
                note.getOriginalInvoiceNumber(), note.getOriginalInvoiceDate(), note.getBuyerName(),
                note.getBuyerGstin(), note.getPlaceOfSupply(), note.getReason(), note.getTaxableValuePaise(),
                note.getCgstPaise(), note.getSgstPaise(), note.getIgstPaise(), note.getCessPaise(),
                note.getRoundOffPaise(), note.getGrandTotalPaise(),
                note.getItems().stream()
                        .map(item -> new Line(item.getLineNo(), item.getOriginalLineNo(), item.getProduct().getId(),
                                item.getSku(), item.getProductName(), item.getHsnCode(), item.getUnit(),
                                item.getQuantityMilli(), item.getUnitPricePaise(), item.getDiscountPaise(),
                                item.getGstRateBp(), item.getTaxableValuePaise(), item.getCgstPaise(),
                                item.getSgstPaise(), item.getIgstPaise(), item.getCessPaise(),
                                item.getLineTotalPaise()))
                        .toList(),
                note.getRefunds().stream()
                        .map(refund -> new Refund(refund.getMode(), refund.getAmountPaise(), refund.getReference()))
                        .toList());
    }
}
