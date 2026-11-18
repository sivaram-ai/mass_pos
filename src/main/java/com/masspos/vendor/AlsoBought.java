package com.masspos.vendor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a vendor has sold the shop that no stock ledger counts.
 *
 * <p>A vendor's balance is summed from the two ledgers, because nearly everything the shop buys is
 * counted on a shelf or in a store. A one-off is not: a gas regulator, an hour of a plumber's
 * time, a box of something the shop will never buy again. The money is just as real and the vendor
 * is just as owed, so something has to tell the balance about it.
 *
 * <p>An interface here rather than the vendor package reading the purchase package: purchases
 * already read vendors, and the two reading each other is the kind of knot that is easy to tie and
 * hard to undo. The purchase package implements it; this side only knows that somebody will.
 */
public interface AlsoBought {

    /** What one-off purchases have come to, per vendor, in paise. */
    Map<UUID, Long> perVendor();

    /** One vendor's, for his own page, newest first. */
    List<VendorProfile.Purchase> of(UUID vendorId);
}
