package com.masspos.billing;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    /** Rate-wise tax for the GSTR-1 summary. Cancelled bills are excluded from every return figure. */
    @Query("""
            select i.gstRateBp, sum(i.taxableValuePaise), sum(i.cgstPaise), sum(i.sgstPaise),
                   sum(i.igstPaise), sum(i.cessPaise), count(i)
            from InvoiceItem i
            where i.invoice.invoiceDate between :from and :to and i.invoice.status = :status
            group by i.gstRateBp
            order by i.gstRateBp
            """)
    List<Object[]> taxByRate(@Param("from") LocalDate from, @Param("to") LocalDate to,
                             @Param("status") InvoiceStatus status);

    /** HSN summary, table 12 of GSTR-1. */
    @Query("""
            select i.hsnCode, i.unit, sum(i.quantityMilli), sum(i.taxableValuePaise), sum(i.cgstPaise),
                   sum(i.sgstPaise), sum(i.igstPaise), sum(i.cessPaise)
            from InvoiceItem i
            where i.invoice.invoiceDate between :from and :to and i.invoice.status = :status
            group by i.hsnCode, i.unit
            order by i.hsnCode
            """)
    List<Object[]> taxByHsn(@Param("from") LocalDate from, @Param("to") LocalDate to,
                            @Param("status") InvoiceStatus status);

    /** What is actually moving off the shelves. */
    @Query("""
            select i.productName, sum(i.quantityMilli), sum(i.lineTotalPaise), count(i)
            from InvoiceItem i
            where i.invoice.invoiceDate between :from and :to and i.invoice.status = :status
            group by i.productName
            order by sum(i.lineTotalPaise) desc
            """)
    List<Object[]> bestSellers(@Param("from") LocalDate from, @Param("to") LocalDate to,
                               @Param("status") InvoiceStatus status, Limit limit);
}
