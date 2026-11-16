package com.masspos.inventory;

import com.masspos.client.CurrentCounter;
import com.masspos.auth.AuthContext;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.user.User;
import com.masspos.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stock, derived from the ledger rather than stored. Every movement is one immutable event, so
 * tills that were offline merge by union instead of overwriting each other's counts.
 */
@Service
public class InventoryService {

    private final InventoryLedgerRepository ledger;
    private final ProductRepository products;
    private final UserRepository users;
    private final CurrentCounter counter;

    public InventoryService(InventoryLedgerRepository ledger, ProductRepository products, UserRepository users,
                            CurrentCounter counter) {
        this.ledger = ledger;
        this.products = products;
        this.users = users;
        this.counter = counter;
    }

    @Transactional(readOnly = true)
    public long onHand(UUID productId) {
        return ledger.onHand(productId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> onHandFor(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return toMap(ledger.onHandFor(productIds));
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> onHandForAll() {
        return toMap(ledger.onHandForAll());
    }

    /**
     * Everything that has ever moved one item, newest first, each row carrying what the shelf held
     * once it had been applied.
     *
     * <p>The running count is walked backwards from today's: the newest movement left the shelf at
     * the count it is on now, and each older one left it at the count after it less that movement.
     * It is only honest over a whole history, which is why this takes no date window - the screen
     * filters and searches within what comes back.
     */
    @Transactional(readOnly = true)
    public List<StockMovementView> historyOf(UUID productId) {
        List<InventoryLedgerEvent> events = ledger.findByProductIdOrderByOccurredAtDesc(productId);
        long running = events.stream().mapToLong(InventoryLedgerEvent::getQuantityDeltaMilli).sum();
        List<StockMovementView> history = new ArrayList<>(events.size());
        for (InventoryLedgerEvent event : events) {
            history.add(StockMovementView.of(event, running));
            running -= event.getQuantityDeltaMilli();
        }
        return history;
    }

    /** Every item's movements over a window, for somebody asking what the shop has been doing. */
    @Transactional(readOnly = true)
    public List<StockMovementView> historySince(Instant from) {
        return ledger.findByOccurredAtBetweenOrderByOccurredAtDesc(from, Instant.now()).stream()
                .map(event -> StockMovementView.of(event, null))
                .toList();
    }

    /** Goods arriving from a supplier. */
    @Transactional
    public InventoryLedgerEvent receive(UUID productId, long quantityMilli, String note) {
        return record(productId, LedgerEventType.PURCHASE_RECEIPT, quantityMilli, null, note);
    }

    /**
     * Stock-take correction, either direction. The difference is recorded as its own event, so the
     * count before the correction stays visible in the ledger.
     */
    @Transactional
    public InventoryLedgerEvent adjust(UUID productId, long deltaMilli, String note) {
        return record(productId, LedgerEventType.ADJUSTMENT, deltaMilli, null, note);
    }

    @Transactional
    public InventoryLedgerEvent damage(UUID productId, long quantityMilli, String note) {
        return record(productId, LedgerEventType.DAMAGE, -Math.abs(quantityMilli), null, note);
    }

    /**
     * Takes a sale off the shelf, and never past empty.
     *
     * <p>A count below zero is not a fact about a shop. It is what you get by subtracting from
     * something that had already run out, and it compounds: one restaurant's screen read -357 of a
     * biriyani it cooks to order, which tells nobody anything and makes every number beside it
     * worth ignoring. The shelf stops at empty, and the sale goes through - that is what billing
     * without stock means.
     *
     * <p>The bill is untouched by this, and the bill is the record of what was sold: the money, the
     * tax and the customer's copy all carry the full quantity. This is the record of what the shelf
     * had, which is a different question. The two can be compared - the invoice line against the
     * ledger - so what went out over and above the count is still there to be found.
     *
     * @return what was actually taken off, which is less than asked for when the shelf runs out
     */
    @Transactional
    public long sell(UUID productId, long quantityMilli, UUID invoiceId, String invoiceNumber) {
        long onShelf = Math.max(0, onHand(productId));
        long take = Math.min(onShelf, quantityMilli);
        if (take <= 0) {
            // Nothing to take off. A zero event is refused, and would say nothing anyway.
            return 0;
        }
        // The bill is named on the movement, the way a return and a cancellation already name
        // theirs. It is what the history screen answers "where did five of these go" with, and it
        // costs one string here instead of the inventory package reading the billing package back.
        String note = "Bill " + invoiceNumber;
        record(productId, LedgerEventType.SALE, -take, invoiceId,
                take < quantityMilli ? note + " - the shelf ran out, so only part of it came off the count" : note);
        return take;
    }

    /**
     * Puts back exactly what a document took off the shelf, which is what a cancelled bill means:
     * nothing physically moved, so the count goes back to where it was. Reversing the quantities on
     * the bill instead would conjure stock out of a shelf that was empty when it was sold from.
     *
     * <p>Per product rather than per line: the same item on two lines of one bill is one count.
     */
    @Transactional
    public void putBack(UUID invoiceId, String note) {
        for (Map.Entry<UUID, Long> tookOff : toMap(ledger.movedBy(invoiceId, LedgerEventType.SALE)).entrySet()) {
            long back = -tookOff.getValue();
            if (back > 0) {
                record(tookOff.getKey(), LedgerEventType.SALE_CANCELLED, back, invoiceId, note);
            }
        }
    }

    /** Used by the sale and cancellation paths, which pass the invoice as the source document. */
    @Transactional
    public InventoryLedgerEvent record(UUID productId, LedgerEventType type, long deltaMilli, UUID referenceId,
                                       String note) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("No such product: " + productId));
        return ledger.save(new InventoryLedgerEvent(product, type, deltaMilli, referenceId, counter.code(),
                Instant.now(), currentUser(), note));
    }

    private User currentUser() {
        return users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("Signed-in user has disappeared"));
    }

    private static Map<UUID, Long> toMap(List<Object[]> rows) {
        Map<UUID, Long> onHand = new HashMap<>();
        for (Object[] row : rows) {
            onHand.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return onHand;
    }
}
