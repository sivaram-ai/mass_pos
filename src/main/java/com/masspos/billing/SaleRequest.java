package com.masspos.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A cart being taken as a bill.
 *
 * @param placeOfSupply state code; empty for a walk-in customer, which means the seller's own state
 * @param print         print the receipt; a printer failure never undoes the sale
 * @param openDrawer    kick the drawer, normally set when any part of the bill is cash
 */
public record SaleRequest(@NotEmpty List<@Valid Line> lines,
                          @ValidGstin String buyerGstin,
                          @Size(max = 100) String buyerName,
                          @Pattern(regexp = "|\\d{2}", message = "must be a two-digit state code") String placeOfSupply,
                          @NotEmpty List<@Valid Payment> payments,
                          boolean print,
                          boolean openDrawer) {

    /**
     * @param unitPricePaise overrides the catalogue price for this line only, e.g. a haggled price
     *                       or a damaged-goods discount. Null uses the shelf price.
     */
    public record Line(@NotNull UUID productId, @Positive long quantityMilli, @PositiveOrZero long discountPaise,
                       Long unitPricePaise) {
    }

    public record Payment(@NotNull PaymentMode mode, @Positive long amountPaise,
                          @PositiveOrZero long tenderedPaise, @Size(max = 60) String reference) {
    }
}
