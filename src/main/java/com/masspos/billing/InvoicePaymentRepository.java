package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, UUID> {

    /** What the drawer and each settlement account should hold at closing time. */
    @Query("""
            select p.mode, count(p), sum(p.amountPaise)
            from InvoicePayment p
            where p.invoice.invoiceDate between :from and :to and p.invoice.status = :status
            group by p.mode
            order by p.mode
            """)
    List<Object[]> byMode(@Param("from") LocalDate from, @Param("to") LocalDate to,
                          @Param("status") InvoiceStatus status);
}
