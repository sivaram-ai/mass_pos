package com.masspos.rawmaterial;

import com.masspos.catalog.UnitOfMeasure;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement in a raw material's history.
 *
 * <p>Deliberately the same shape as {@code StockMovementView}, field for field, so that one
 * History screen serves the shelf and the store. The two have different tables and different
 * vocabularies underneath and should: what they have in common is the question somebody is asking
 * of them, which is "why does it say four".
 */
public record RawMaterialMovementView(UUID id, Instant occurredAt, RawMaterialMovement type,
                                      long quantityDeltaMilli, Long balanceAfterMilli, UUID itemId,
                                      String itemCode, String name, UnitOfMeasure unit, String counterCode,
                                      String by, String note, long buyingPricePaise, String vendorName,
                                      String orderNumber) {

    static RawMaterialMovementView of(RawMaterialLedgerEvent event, Long balanceAfterMilli) {
        RawMaterial material = event.getRawMaterial();
        return new RawMaterialMovementView(event.getId(), event.getOccurredAt(), event.getType(),
                event.getQuantityDeltaMilli(), balanceAfterMilli, material.getId(), material.getCode(),
                material.getName(), material.getUnit(), event.getTerminalCode(),
                event.getRecordedBy() == null ? "" : event.getRecordedBy().getDisplayName(),
                event.getNote(), event.getBuyingPricePaise(), event.getVendorName(), event.getOrderNumber());
    }
}
