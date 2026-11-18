package com.masspos.purchase;

import com.masspos.catalog.UnitOfMeasure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An order as the screen reads it.
 *
 * @param valuePaise  what it is expected to come to, at the costs written on it
 * @param decision    why it was turned down or called off, where it was
 * @param owedPaise   what the vendor can ask to be paid for it: what arrived once it has been
 *                    received, and what was asked for before that
 * @param paidPaise   what an administrator has agreed was paid against this order. Money paid
 *                    against the vendor's running account rather than one order is not here, and
 *                    is not meant to be: it is in the vendor's balance.
 * @param settled     where that leaves it, which is the one thing a shop asks of an old order
 */
public record PurchaseOrderView(UUID id, String number, PurchaseOrderStatus status, UUID vendorId,
                                String vendorName, LocalDate orderedOn, LocalDate expectedOn, String note,
                                long valuePaise, String createdBy, String decidedBy, Instant decidedAt,
                                String decision, String receivedBy, Instant receivedAt,
                                long owedPaise, long paidPaise, Settled settled, List<Line> lines) {

    /**
     * Whether the money has followed the goods.
     *
     * <p>Worked out rather than stored, from the payments an administrator has agreed to and
     * allocated to this order. A shop that pays its vendors off a running account will leave
     * every order UNPAID and should: the honest answer to "has this order been paid" is then
     * "nobody said so", and the vendor's balance is where that shop looks.
     */
    public enum Settled {
        /** Nothing has been allocated to it. */
        UNPAID,
        /** Some of it has. */
        PART_PAID,
        /** All of it, or more. */
        PAID,
        /** Turned down or called off: nothing is owed on it at all. */
        NOT_DUE
    }

    /**
     * @param receivedMilli what actually turned up, which may be less than what was asked for
     */
    public record Line(UUID id, boolean fromTheStore, boolean oneOff, UUID itemId, String itemCode,
                       String name, UnitOfMeasure unit, long quantityMilli, long unitCostPaise,
                       long valuePaise, long receivedMilli) {
    }

    static PurchaseOrderView of(PurchaseOrder order, long paidPaise, long sentBackPaise) {
        long owed = Math.max(0, owedOn(order) - sentBackPaise);
        return new PurchaseOrderView(order.getId(), order.getNumber(), order.getStatus(),
                order.getVendor().getId(), order.getVendor().getName(), order.getOrderedOn(),
                order.getExpectedOn(), order.getNote(), order.getValuePaise(),
                who(order.getCreatedBy()), who(order.getDecidedBy()), order.getDecidedAt(),
                order.getDecision(), who(order.getReceivedBy()), order.getReceivedAt(),
                owed, paidPaise, settled(owed, paidPaise), 
                order.getLines().stream().map(PurchaseOrderView::lineOf).toList());
    }

    /**
     * Nothing is owed until the goods are in the shop, and nothing is owed for what went back.
     *
     * <p>An order waiting for an administrator is a request, and one that has been approved is a
     * promise; neither is a debt, and a screen that called them "not paid" would have every shop
     * looking at a figure it does not owe anybody. What is owed is what actually arrived, at the
     * costs on the order - a lorry that brought eight of the twelve is owed for eight.
     */
    private static long owedOn(PurchaseOrder order) {
        return order.getStatus() == PurchaseOrderStatus.RECEIVED ? order.getReceivedValuePaise() : 0;
    }

    private static Settled settled(long owedPaise, long paidPaise) {
        if (owedPaise <= 0) {
            // Money against an order nothing is owed on is a shop paying in advance, which is
            // ordinary enough with a vendor who wants it before he loads the lorry.
            return paidPaise > 0 ? Settled.PAID : Settled.NOT_DUE;
        }
        if (paidPaise <= 0) {
            return Settled.UNPAID;
        }
        return paidPaise >= owedPaise ? Settled.PAID : Settled.PART_PAID;
    }

    private static Line lineOf(PurchaseOrderLine line) {
        return new Line(line.getId(), line.isFromTheStore(), line.isOneOff(), itemOf(line),
                line.getItemCode(), line.getItemName(), line.getUnit(), line.getQuantityMilli(),
                line.getUnitCostPaise(), line.getValuePaise(), line.getReceivedMilli());
    }

    /** Null on a one-off: there is no item for the screen to open. */
    private static UUID itemOf(PurchaseOrderLine line) {
        if (line.isOneOff()) {
            return null;
        }
        return line.isFromTheStore() ? line.getRawMaterial().getId() : line.getProduct().getId();
    }

    private static String who(com.masspos.user.User user) {
        return user == null ? "" : user.getDisplayName();
    }
}
