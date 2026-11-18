package com.masspos.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface HeldPurchaseOrderRepository extends JpaRepository<HeldPurchaseOrder, UUID> {

    /**
     * Newest first, with the id breaking the tie - the same reason held bills need it:
     * {@code createdAt} is a millisecond, two held in the same breath share one, and the order was
     * then whatever the database felt like. Every key here is a UUIDv7, which carries its own
     * timestamp in its leading bits and sorts in the order the rows were made.
     */
    @Query("""
            select h from HeldPurchaseOrder h
            where h.terminalCode = :terminalCode
            order by h.createdAt desc, h.id desc
            """)
    List<HeldPurchaseOrder> heldOn(@Param("terminalCode") String terminalCode);
}
