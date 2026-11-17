package com.masspos.vendor;

import java.util.UUID;

/**
 * A vendor as the list shows them, with what the shop has bought and what it still owes.
 *
 * @param boughtPaise        everything ever bought from them, at what it was bought for
 * @param paidPaise          what an administrator has agreed was paid
 * @param waitingPaise       payments recorded but not yet agreed to. Deliberately its own figure
 *                           and not folded into either of the others: the shop should see that
 *                           somebody says they paid, without the books moving on one person's
 *                           say-so.
 * @param outstandingPaise   bought less paid. Negative means the shop has paid ahead.
 */
public record VendorView(UUID id, String code, String name, String phone, String gstin, String address,
                         String note, boolean active, long boughtPaise, long paidPaise, long waitingPaise,
                         long outstandingPaise) {

    static VendorView of(Vendor vendor, long boughtPaise, long paidPaise, long waitingPaise) {
        return new VendorView(vendor.getId(), vendor.getCode(), vendor.getName(), vendor.getPhone(),
                vendor.getGstin(), vendor.getAddress(), vendor.getNote(), vendor.isActive(),
                boughtPaise, paidPaise, waitingPaise, boughtPaise - paidPaise);
    }
}
