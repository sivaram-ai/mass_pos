package com.masspos.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Goods coming back, priced for a quote or issued as a credit note.
 *
 * @param againstInvoiceId the bill they were sold on; null for a return without the bill, which is
 *                         priced like a sale at today's rates
 * @param refunds          how the money goes back; must add up to the return. Not needed for a quote
 * @param openDrawer       kick the drawer, normally when any of it is paid back in cash
 */
public record ReturnRequest(UUID againstInvoiceId,
                            @NotEmpty List<@Valid Line> lines,
                            List<@Valid Refund> refunds,
                            @Size(max = 200) String reason,
                            boolean print,
                            boolean openDrawer) {

    /**
     * @param originalLineNo   which line of the bill the item is from; required with a bill
     * @param unitPricePaise   for a return without a bill, the price paid; null takes the shelf price
     */
    public record Line(@NotNull UUID productId, Integer originalLineNo, @Positive long quantityMilli,
                       @PositiveOrZero long discountPaise, Long unitPricePaise) {
    }

    public record Refund(@NotNull PaymentMode mode, @Positive long amountPaise, @Size(max = 60) String reference) {
    }

    public List<Refund> refundsOrNone() {
        return refunds == null ? List.of() : refunds;
    }
}
