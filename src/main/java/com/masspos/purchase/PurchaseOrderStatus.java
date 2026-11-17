package com.masspos.purchase;

/**
 * Where a purchase order has got to.
 *
 * <p>Deliberately short. An order is asked for, somebody says yes or no, and the goods turn up -
 * three things that happen in a shop, not a workflow engine. Anything finer than this would be
 * states nobody at a counter could name.
 */
public enum PurchaseOrderStatus {
    /** Written by a manager, waiting for an administrator to agree to it. */
    PENDING_APPROVAL,
    /** Agreed to. The only state goods can be received against. */
    APPROVED,
    /** Turned down, with a reason. Nothing further happens to it. */
    REJECTED,
    /** The goods came in and the counts moved. The end of it. */
    RECEIVED,
    /** Called off before the goods came. */
    CANCELLED
}
