package com.masspos.rawmaterial;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RawMaterialLedgerRepository extends JpaRepository<RawMaterialLedgerEvent, UUID> {

    /** What is in the store: the sum of every movement ever recorded for the material. */
    @Query("""
            select coalesce(sum(e.quantityDeltaMilli), 0)
            from RawMaterialLedgerEvent e where e.rawMaterial.id = :materialId
            """)
    long inStore(@Param("materialId") UUID materialId);

    /** One row per material, so the whole list needs a single query. */
    @Query("""
            select e.rawMaterial.id, coalesce(sum(e.quantityDeltaMilli), 0)
            from RawMaterialLedgerEvent e
            group by e.rawMaterial.id
            """)
    List<Object[]> inStoreForAll();

    /** Everything had from one vendor, newest first - what came in and what went back. */
    @Query("""
            select e from RawMaterialLedgerEvent e
            where e.type in (com.masspos.rawmaterial.RawMaterialMovement.PURCHASE,
                             com.masspos.rawmaterial.RawMaterialMovement.RETURNED_TO_VENDOR)
              and (e.vendorId = :vendorId or (e.vendorId is null and e.vendorName = :name))
            order by e.occurredAt desc
            """)
    List<RawMaterialLedgerEvent> boughtFrom(@Param("vendorId") UUID vendorId, @Param("name") String name);

    /** See the shelf's version: summed first, divided by the caller. */
    @Query("""
            select e.vendorId, coalesce(sum(e.quantityDeltaMilli * e.buyingPricePaise), 0)
            from RawMaterialLedgerEvent e
            where e.type in (com.masspos.rawmaterial.RawMaterialMovement.PURCHASE,
                             com.masspos.rawmaterial.RawMaterialMovement.RETURNED_TO_VENDOR)
              and e.vendorId is not null
            group by e.vendorId
            """)
    List<Object[]> boughtPerVendorId();

    @Query("""
            select e.vendorName, coalesce(sum(e.quantityDeltaMilli * e.buyingPricePaise), 0)
            from RawMaterialLedgerEvent e
            where e.type in (com.masspos.rawmaterial.RawMaterialMovement.PURCHASE,
                             com.masspos.rawmaterial.RawMaterialMovement.RETURNED_TO_VENDOR)
              and e.vendorId is null and e.vendorName <> ''
            group by e.vendorName
            """)
    List<Object[]> boughtPerVendorName();


    /**
     * Spelled out rather than derived from the method name: {@code findByRawMaterialId...} reads
     * {@code rawMaterialId} as one attribute, which the entity does not have, and the repository
     * then refuses to be created at all - taking the whole application down at startup.
     */
    @Query("""
            select e from RawMaterialLedgerEvent e
            where e.rawMaterial.id = :materialId
            order by e.occurredAt desc
            """)
    List<RawMaterialLedgerEvent> historyOf(@Param("materialId") UUID materialId);

    @Query("""
            select e from RawMaterialLedgerEvent e
            where e.occurredAt between :from and :to
            order by e.occurredAt desc
            """)
    List<RawMaterialLedgerEvent> between(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Every delivery of one material, newest first: what it cost each time and who it came from.
     * The answer to "are we paying more for onions than we were", which is a question about the
     * deliveries and not about the item.
     */
    @Query("""
            select e from RawMaterialLedgerEvent e
            where e.rawMaterial.id = :materialId
              and e.type = com.masspos.rawmaterial.RawMaterialMovement.PURCHASE
            order by e.occurredAt desc
            """)
    List<RawMaterialLedgerEvent> deliveriesOf(@Param("materialId") UUID materialId);
}
