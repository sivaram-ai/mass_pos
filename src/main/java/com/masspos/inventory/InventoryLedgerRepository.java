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

    /** Everything bought from one vendor, newest first, for that vendor's own page. */
    @Query("""
            select e from InventoryLedgerEvent e
            where e.type = com.masspos.inventory.LedgerEventType.PURCHASE_RECEIPT
              and (e.vendorId = :vendorId or (e.vendorId is null and e.vendorName = :name))
            order by e.occurredAt desc
            """)
    List<InventoryLedgerEvent> boughtFrom(@Param("vendorId") UUID vendorId, @Param("name") String name);

    /**
     * What every vendor on the list has been bought from, in quantity x unit price. Divided by
     * 1000 by the caller, after the sum: milli-units times paise is a thousand times the money,
     * and dividing each row first would throw away a fraction of every delivery.
     */
    @Query("""
            select e.vendorId, coalesce(sum(e.quantityDeltaMilli * e.buyingPricePaise), 0)
            from InventoryLedgerEvent e
            where e.type = com.masspos.inventory.LedgerEventType.PURCHASE_RECEIPT and e.vendorId is not null
            group by e.vendorId
            """)
    List<Object[]> boughtPerVendorId();

    /** And the same for deliveries taken before the shop kept a list, which carry only a name. */
    @Query("""
            select e.vendorName, coalesce(sum(e.quantityDeltaMilli * e.buyingPricePaise), 0)
            from InventoryLedgerEvent e
            where e.type = com.masspos.inventory.LedgerEventType.PURCHASE_RECEIPT
              and e.vendorId is null and e.vendorName <> ''
            group by e.vendorName
            """)
    List<Object[]> boughtPerVendorName();


    /**
     * Every delivery of one item, newest first: what it cost each time and who it came from. The
     * answer to "are we paying more for these than we were", which is a question about the
     * deliveries and not about the item, whose price is only what it is today.
     */
    @Query("""
            select e from InventoryLedgerEvent e
            where e.product.id = :productId
              and e.type = com.masspos.inventory.LedgerEventType.PURCHASE_RECEIPT
            order by e.occurredAt desc
            """)
    List<InventoryLedgerEvent> deliveriesOf(@Param("productId") UUID productId);

    List<InventoryLedgerEvent> findByProductIdOrderByOccurredAtDesc(UUID productId);

    List<InventoryLedgerEvent> findByOccurredAtBetweenOrderByOccurredAtDesc(Instant from, Instant to);
}
