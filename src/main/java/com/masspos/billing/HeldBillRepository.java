package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface HeldBillRepository extends JpaRepository<HeldBill, UUID> {

    /**
     * Newest first, and the id breaks the tie.
     *
     * <p>{@code createdAt} is a millisecond, and two bills held in the same breath - a cashier
     * putting a table aside and immediately starting the next one - share it. The order was then
     * whatever the database felt like, so the newest bill could be listed second. The id settles
     * it: every key here is a UUIDv7, which carries its own timestamp in its leading bits and so
     * sorts in the order the rows were made.
     */
    @Query("""
            select h from HeldBill h
            where h.terminalCode = :terminalCode
            order by h.createdAt desc, h.id desc
            """)
    List<HeldBill> heldOn(@Param("terminalCode") String terminalCode);
}
