package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CreditNoteRefundRepository extends JpaRepository<CreditNoteRefund, UUID> {

    /** Money paid back per mode, to net off what the drawer and each account took in. */
    @Query("""
            select r.mode, count(r), sum(r.amountPaise)
            from CreditNoteRefund r
            where r.creditNote.noteDate between :from and :to
            group by r.mode
            """)
    List<Object[]> byMode(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
