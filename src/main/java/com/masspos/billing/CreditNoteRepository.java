package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CreditNoteRepository extends JpaRepository<CreditNote, UUID> {

    List<CreditNote> findByNoteDateBetweenOrderByIssuedAtDesc(LocalDate from, LocalDate to);

    long countByNoteDateBetween(LocalDate from, LocalDate to);

    List<CreditNote> findByOriginalInvoiceIdOrderByIssuedAtAsc(UUID invoiceId);

    @Query("""
            select sum(c.taxableValuePaise), sum(c.cgstPaise), sum(c.sgstPaise), sum(c.igstPaise),
                   sum(c.cessPaise), sum(c.roundOffPaise), sum(c.grandTotalPaise)
            from CreditNote c
            where c.noteDate between :from and :to
            """)
    List<Object[]> totals(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select c.cashier.displayName, count(c), sum(c.grandTotalPaise)
            from CreditNote c
            where c.noteDate between :from and :to
            group by c.cashier.displayName
            """)
    List<Object[]> refundsByCashier(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select c.terminalCode, count(c), sum(c.grandTotalPaise)
            from CreditNote c
            where c.noteDate between :from and :to
            group by c.terminalCode
            """)
    List<Object[]> refundsByTerminal(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
