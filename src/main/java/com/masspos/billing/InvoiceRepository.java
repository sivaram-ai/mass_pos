package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    List<Invoice> findByInvoiceDateBetweenOrderByIssuedAtDesc(LocalDate from, LocalDate to);

    List<Invoice> findByInvoiceDateOrderByIssuedAtDesc(LocalDate date);

    Optional<Invoice> findFirstByTerminalCodeOrderByIssuedAtDesc(String terminalCode);

    /** The bill issued in place of this one when it was edited. */
    Optional<Invoice> findFirstByReplacesInvoiceNumber(String invoiceNumber);

    long countByInvoiceDateBetweenAndStatus(LocalDate from, LocalDate to, InvoiceStatus status);

    @Query("""
            select sum(i.taxableValuePaise), sum(i.cgstPaise), sum(i.sgstPaise), sum(i.igstPaise),
                   sum(i.cessPaise), sum(i.roundOffPaise), sum(i.grandTotalPaise)
            from Invoice i
            where i.invoiceDate between :from and :to and i.status = :status
            """)
    List<Object[]> totals(@Param("from") LocalDate from, @Param("to") LocalDate to,
                          @Param("status") InvoiceStatus status);

    /** Who billed how much: the figure a cashier is handed over against at the end of a shift. */
    @Query("""
            select i.cashier.displayName, count(i), sum(i.grandTotalPaise)
            from Invoice i
            where i.invoiceDate between :from and :to and i.status = :status
            group by i.cashier.displayName
            order by sum(i.grandTotalPaise) desc
            """)
    List<Object[]> takingsByCashier(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                    @Param("status") InvoiceStatus status);

    /** Per counter, so a master can compare its tills and floors. */
    @Query("""
            select i.terminalCode, count(i), sum(i.grandTotalPaise)
            from Invoice i
            where i.invoiceDate between :from and :to and i.status = :status
            group by i.terminalCode
            order by i.terminalCode
            """)
    List<Object[]> takingsByTerminal(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                     @Param("status") InvoiceStatus status);
}
