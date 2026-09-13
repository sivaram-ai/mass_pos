package com.masspos.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A bill as it should have been. The original is cancelled and this is issued in its place.
 *
 * @param payments only what is collected on top of the original bill's payments, which carry over;
 *                 empty when the new bill costs the same or less
 * @param reason   why it was changed, kept with the cancelled bill
 */
public record EditRequest(@NotEmpty List<SaleRequest.@Valid Line> lines,
                          @ValidGstin String buyerGstin,
                          @Size(max = 100) String buyerName,
                          @Pattern(regexp = "|\\d{2}", message = "must be a two-digit state code") String placeOfSupply,
                          List<SaleRequest.@Valid Payment> payments,
                          @Size(max = 120) String reason,
                          boolean print,
                          boolean openDrawer) {

    public List<SaleRequest.Payment> paymentsOrNone() {
        return payments == null ? List.of() : payments;
    }
}
