package com.masspos.billing;

import com.masspos.catalog.UnitOfMeasure;

import java.util.List;
import java.util.UUID;

/** Priced cart: the same arithmetic the bill will use, without issuing anything. */
public record QuotedCart(List<QuotedLine> lines, boolean interState, long taxableValuePaise, long cgstPaise,
                         long sgstPaise, long igstPaise, long cessPaise, long roundOffPaise, long grandTotalPaise) {

    public record QuotedLine(UUID productId, String sku, String name, String hsnCode, UnitOfMeasure unit,
                             long quantityMilli, long unitPricePaise, long discountPaise, int gstRateBp,
                             long taxableValuePaise, long taxPaise, long lineTotalPaise) {
    }
}
