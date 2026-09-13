package com.masspos.inventory;

import com.masspos.auth.AuthContext;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.config.PosProperties;
import com.masspos.user.User;
import com.masspos.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final PosProperties pos;

    public InventoryService(InventoryLedgerRepository ledger, ProductRepository products, UserRepository users,
                            PosProperties pos) {
        this.ledger = ledger;
        this.products = products;
        this.users = users;
        this.pos = pos;
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

    /** Used by the sale and cancellation paths, which pass the invoice as the source document. */
    @Transactional
    public InventoryLedgerEvent record(UUID productId, LedgerEventType type, long deltaMilli, UUID referenceId,
                                       String note) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("No such product: " + productId));
        return ledger.save(new InventoryLedgerEvent(product, type, deltaMilli, referenceId, pos.terminal().code(),
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
