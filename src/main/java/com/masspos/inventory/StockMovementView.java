package com.masspos.inventory;

import com.masspos.catalog.Product;
import com.masspos.catalog.UnitOfMeasure;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement in an item's history, as the History screen reads it.
 *
 * <p>A count on its own is a number somebody has to take on trust. This is the answer to "it says
 * four and there are six": the goods that came in, the bills that took them out, the damage, and
 * the corrections somebody made by hand - each with the till it happened on, the person who did it
 * and the time. Nothing here is computed from anything but the ledger, which is what the count
 * itself is summed from.
 *
 * @param balanceAfterMilli what the shelf held once this movement had been applied, or null where
 *                          the whole of an item's history was not asked for: a running count means
 *                          nothing over a window that starts in the middle of one.
 * @param note              what the movement was for, in the words of whoever recorded it. Sales,
 *                          returns and cancellations name their document here, which is how the
 *                          screen answers "which bill took these five off".
 */
public record StockMovementView(UUID id, Instant occurredAt, LedgerEventType type, long quantityDeltaMilli,
                                Long balanceAfterMilli, UUID productId, String sku, String name, UnitOfMeasure unit,
                                String counterCode, String by, String note) {

    static StockMovementView of(InventoryLedgerEvent event, Long balanceAfterMilli) {
        Product product = event.getProduct();
        return new StockMovementView(event.getId(), event.getOccurredAt(), event.getType(),
                event.getQuantityDeltaMilli(), balanceAfterMilli, product.getId(), product.getSku(),
                product.getName(), product.getUnit(), event.getTerminalCode(),
                event.getRecordedBy() == null ? "" : event.getRecordedBy().getDisplayName(),
                event.getNote() == null ? "" : event.getNote());
    }
}
