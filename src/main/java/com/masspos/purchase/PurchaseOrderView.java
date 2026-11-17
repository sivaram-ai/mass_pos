package com.masspos.purchase;

import com.masspos.catalog.UnitOfMeasure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An order as the screen reads it.
 *
 * @param valuePaise what it is expected to come to, at the costs written on it
 * @param decision   why it was turned down or called off, where it was
 */
public record PurchaseOrderView(UUID id, String number, PurchaseOrderStatus status, UUID vendorId,
                                String vendorName, LocalDate orderedOn, LocalDate expectedOn, String note,
                                long valuePaise, String createdBy, String decidedBy, Instant decidedAt,
                                String decision, String receivedBy, Instant receivedAt, List<Line> lines) {

    /**
     * @param receivedMilli what actually turned up, which may be less than what was asked for
     */
    public record Line(UUID id, boolean fromTheStore, UUID itemId, String itemCode, String name,
                       UnitOfMeasure unit, long quantityMilli, long unitCostPaise, long valuePaise,
                       long receivedMilli) {
    }

    static PurchaseOrderView of(PurchaseOrder order) {
        return new PurchaseOrderView(order.getId(), order.getNumber(), order.getStatus(),
                order.getVendor().getId(), order.getVendor().getName(), order.getOrderedOn(),
                order.getExpectedOn(), order.getNote(), order.getValuePaise(),
                who(order.getCreatedBy()), who(order.getDecidedBy()), order.getDecidedAt(),
                order.getDecision(), who(order.getReceivedBy()), order.getReceivedAt(),
                order.getLines().stream().map(PurchaseOrderView::lineOf).toList());
    }

    private static Line lineOf(PurchaseOrderLine line) {
        return new Line(line.getId(), line.isFromTheStore(),
                line.isFromTheStore() ? line.getRawMaterial().getId() : line.getProduct().getId(),
                line.getItemCode(), line.getItemName(), line.getUnit(), line.getQuantityMilli(),
                line.getUnitCostPaise(), line.getValuePaise(), line.getReceivedMilli());
    }

    private static String who(com.masspos.user.User user) {
        return user == null ? "" : user.getDisplayName();
    }
}
