package com.masspos.purchase;

import com.masspos.catalog.UnitOfMeasure;
import com.masspos.user.User;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Goods that went back, as the screen reads them.
 *
 * @param againstOrderNumber the order they came in on, or empty where nobody said
 * @param writtenAt          the moment it was written down, which is where a history places it:
 *                           the day on its own would sort it among the morning's events
 * @param valuePaise         what went back came to, which is what came off the vendor's balance
 */
public record PurchaseReturnView(UUID id, String number, UUID vendorId, String vendorName,
                                 UUID againstOrderId, String againstOrderNumber, LocalDate returnedOn,
                                 Instant writtenAt, String reason, String by, long valuePaise,
                                 List<Line> lines) {

    public record Line(UUID id, boolean fromTheStore, boolean oneOff, UUID itemId, String itemCode,
                       String name, UnitOfMeasure unit, long quantityMilli, long unitCostPaise,
                       long valuePaise) {
    }

    /**
     * What can still go back off an order: what actually arrived, less whatever has gone back
     * already. The same shape a return's own line has, so one screen draws both.
     */
    public record CanGoBack(UUID lineId, boolean fromTheStore, boolean oneOff, UUID itemId, String itemCode,
                            String name, UnitOfMeasure unit, long receivedMilli, long alreadyBackMilli,
                            long unitCostPaise) {
    }

    static PurchaseReturnView of(PurchaseReturn sentBack) {
        return new PurchaseReturnView(sentBack.getId(), sentBack.getNumber(),
                sentBack.getVendor() == null ? null : sentBack.getVendor().getId(),
                sentBack.getVendorName(), sentBack.getAgainstOrderId(), sentBack.getAgainstOrderNumber(),
                sentBack.getReturnedOn(), sentBack.getCreatedAt(), sentBack.getReason(),
                who(sentBack.getCreatedBy()),
                sentBack.getValuePaise(), sentBack.getLines().stream().map(PurchaseReturnView::lineOf).toList());
    }

    private static Line lineOf(PurchaseReturnLine line) {
        return new Line(line.getId(), line.isFromTheStore(), line.isOneOff(), itemOf(line), line.getItemCode(),
                line.getItemName(), line.getUnit(), line.getQuantityMilli(), line.getUnitCostPaise(),
                line.getValuePaise());
    }

    private static UUID itemOf(PurchaseReturnLine line) {
        if (line.isOneOff()) {
            return null;
        }
        return line.isFromTheStore() ? line.getRawMaterial().getId() : line.getProduct().getId();
    }

    private static String who(User user) {
        return user == null ? "" : user.getDisplayName();
    }
}
