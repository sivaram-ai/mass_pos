package com.masspos.vendor;

import com.masspos.catalog.UnitOfMeasure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the shop knows about one vendor: who they are, what has been bought from them, what
 * has been paid, and what is left.
 *
 * @param purchases newest first, across both the shelf and the store - a vegetable man who sells
 *                  the shop onions for the kitchen and bottled water for the counter is one
 *                  vendor, and his page should not be two
 */
public record VendorProfile(VendorView vendor, List<Purchase> purchases, List<Payment> payments) {

    /**
     * One delivery.
     *
     * @param fromTheStore true when it was a raw material rather than something off the shelf
     */
    public record Purchase(UUID id, Instant occurredAt, boolean fromTheStore, String itemCode, String name,
                           UnitOfMeasure unit, long quantityMilli, long buyingPricePaise, long valuePaise,
                           String note, String by) {
    }

    /** One payment, and whether anybody has agreed to it. */
    public record Payment(UUID id, LocalDate paidOn, long amountPaise, VendorPaymentMode mode, String reference,
                          String note, String by, boolean approved, String approvedBy, Instant approvedAt) {
    }
}
