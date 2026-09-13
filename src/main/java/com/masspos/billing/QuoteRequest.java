package com.masspos.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * A cart being priced while the cashier builds it. The screen never computes tax itself: it asks
 * for this, so what the customer is told matches the bill exactly.
 */
public record QuoteRequest(@NotNull @Valid List<SaleRequest.Line> lines,
                           @Pattern(regexp = "|\\d{2}", message = "must be a two-digit state code")
                           String placeOfSupply) {
}
