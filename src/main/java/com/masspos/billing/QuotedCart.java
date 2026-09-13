package com.masspos.billing;

import com.masspos.catalog.Product;
import com.masspos.catalog.UnitOfMeasure;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Priced cart: the same arithmetic the bill (or the return) will use, without issuing anything. */
public record QuotedCart(List<QuotedLine> lines, boolean interState, long taxableValuePaise, long cgstPaise,
                         long sgstPaise, long igstPaise, long cessPaise, long roundOffPaise, long grandTotalPaise) {

    public record QuotedLine(UUID productId, String sku, String name, String hsnCode, UnitOfMeasure unit,
                             long quantityMilli, long unitPricePaise, long discountPaise, int gstRateBp,
                             long taxableValuePaise, long taxPaise, long lineTotalPaise) {
    }

    /** Adds priced lines up the way an invoice or credit note does, including the round-off. */
    static final class Builder {

        private final boolean interState;
        private final List<QuotedLine> lines = new ArrayList<>();
        private long taxable;
        private long cgst;
        private long sgst;
        private long igst;
        private long cess;

        Builder(boolean interState) {
            this.interState = interState;
        }

        void add(Product product, long quantityMilli, long unitPricePaise, long discountPaise, int gstRateBp,
                 long taxableValuePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise) {
            long tax = cgstPaise + sgstPaise + igstPaise + cessPaise;
            lines.add(new QuotedLine(product.getId(), product.getSku(), product.getName(), product.getHsnCode(),
                    product.getUnit(), quantityMilli, unitPricePaise, discountPaise, gstRateBp, taxableValuePaise, tax,
                    taxableValuePaise + tax));
            taxable += taxableValuePaise;
            cgst += cgstPaise;
            sgst += sgstPaise;
            igst += igstPaise;
            cess += cessPaise;
        }

        QuotedCart build(boolean roundTotal) {
            long total = taxable + cgst + sgst + igst + cess;
            long roundOff = roundTotal ? GstCalculator.roundOffToRupee(total) : 0;
            return new QuotedCart(List.copyOf(lines), interState, taxable, cgst, sgst, igst, cess, roundOff,
                    total + roundOff);
        }
    }
}
