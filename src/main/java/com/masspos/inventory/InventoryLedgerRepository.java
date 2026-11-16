package com.masspos.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InventoryLedgerRepository extends JpaRepository<InventoryLedgerEvent, UUID> {

    /** Stock on hand: the sum of every movement ever recorded for the product. */
    @Query("select coalesce(sum(e.quantityDeltaMilli), 0) from InventoryLedgerEvent e where e.product.id = :productId")
    long onHand(@Param("productId") UUID productId);

    /** One row per product, so a cart or a stock list needs a single query. */
    @Query("""
            select e.product.id, coalesce(sum(e.quantityDeltaMilli), 0)
            from InventoryLedgerEvent e
            where e.product.id in :productIds
            group by e.product.id
            """)
    List<Object[]> onHandFor(@Param("productIds") Collection<UUID> productIds);

    @Query("""
            select e.product.id, coalesce(sum(e.quantityDeltaMilli), 0)
            from InventoryLedgerEvent e
            group by e.product.id
            """)
    List<Object[]> onHandForAll();

    /**
     * What one document actually moved, per product. Not the same as what it sold: a bill for ten
     * of something the shelf had three of takes three off, because the count never goes below
     * empty. Cancelling that bill has to put back the three, not the ten.
     */
    @Query("""
            select e.product.id, coalesce(sum(e.quantityDeltaMilli), 0)
            from InventoryLedgerEvent e
            where e.referenceId = :referenceId and e.type = :type
            group by e.product.id
            """)
    List<Object[]> movedBy(@Param("referenceId") UUID referenceId, @Param("type") LedgerEventType type);

    List<InventoryLedgerEvent> findByProductIdOrderByOccurredAtDesc(UUID productId);

    List<InventoryLedgerEvent> findByOccurredAtBetweenOrderByOccurredAtDesc(Instant from, Instant to);
}
