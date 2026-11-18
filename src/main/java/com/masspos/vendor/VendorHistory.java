package com.masspos.vendor;

import java.time.Instant;

/**
 * Everything that has happened with one vendor, in one line each.
 *
 * <p>A vendor's page answers "what do I owe him". This answers the question underneath it - "why"
 * - and it is the same question the stock history answers about a count: a figure nobody can take
 * apart is a figure nobody can argue with, and the first thing a shopkeeper does when a balance
 * looks wrong is go looking for the delivery or the payment that made it so.
 *
 * <p>Four kinds, gathered from four places that already existed: the orders written to him, the
 * deliveries on both ledgers, the goods that went back, and the money handed over. Nothing here is
 * stored as history - it is assembled from the records themselves, so it can never drift from them.
 */
public final class VendorHistory {

    private VendorHistory() {
    }

    /** The shop's four words for what can happen with somebody it buys from. */
    public enum Kind {
        /** An order was written to him. */
        ORDERED,
        /** Goods arrived, on the shelf or in the store, or something nothing counts. */
        BOUGHT,
        /** Goods went back on the lorry. */
        SENT_BACK,
        /** Money was handed over, whether or not anybody has agreed to it yet. */
        PAID
    }

    /**
     * One line of it.
     *
     * @param reference the order, return or bill number, or how a payment was made
     * @param what      the item, or what the money or the order was for
     * @param state     where it has got to, in the shop's words: an order's status, or whether a
     *                  payment has been approved. Empty where the question does not arise.
     */
    public record Event(String id, Instant when, Kind kind, String reference, String what,
                        long quantityMilli, String unit, long amountPaise, String by, String state) {
    }
}
