package com.masspos.user;

/**
 * What a person may do on the till. Checked by {@code @RequiresRole} on API methods.
 *
 * <p>ADMIN is accepted wherever any role is required, so an owner is never locked out of their own
 * shop; every other role is granted explicitly.
 */
public enum UserRole {
    /** Bills, holds and resumes bills, reprints, opens the drawer with a reason. */
    CASHIER,
    /** Cashier rights plus catalogue, prices, stock, invoice cancellation and day reports. */
    MANAGER,
    /** Read-only: reports, GST return data and the audit trail. Cannot bill or edit. */
    AUDITOR,
    /** Everything, plus users, terminals and company settings. */
    ADMIN
}
