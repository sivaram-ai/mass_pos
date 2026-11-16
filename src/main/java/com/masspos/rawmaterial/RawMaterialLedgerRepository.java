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

    /** Everyone this shop has already bought raw materials from, for the box to suggest from. */
    @Query("""
            select distinct e.vendorName from RawMaterialLedgerEvent e
            where e.vendorName <> ''
            order by e.vendorName
            """)
    List<String> vendorsSoFar();

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
}
