package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CreditNoteItemRepository extends JpaRepository<CreditNoteItem, UUID> {

    /**
     * What has already come back against each line of a bill, with the amounts refunded for it:
     * {@code lineNo, quantity, taxable, cgst, sgst, igst, cess}.
     */
    @Query("""
            select i.originalLineNo, sum(i.quantityMilli), sum(i.taxableValuePaise), sum(i.cgstPaise),
                   sum(i.sgstPaise), sum(i.igstPaise), sum(i.cessPaise)
            from CreditNoteItem i
            where i.creditNote.originalInvoice.id = :invoiceId
            group by i.originalLineNo
            """)
    List<Object[]> returnedAgainst(@Param("invoiceId") UUID invoiceId);

    /** Rate-wise tax taken back, to net off the sales in the GST summary. */
    @Query("""
            select i.gstRateBp, sum(i.taxableValuePaise), sum(i.cgstPaise), sum(i.sgstPaise),
                   sum(i.igstPaise), sum(i.cessPaise), count(i)
            from CreditNoteItem i
            where i.creditNote.noteDate between :from and :to
            group by i.gstRateBp
            """)
    List<Object[]> taxByRate(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select i.hsnCode, i.unit, sum(i.quantityMilli), sum(i.taxableValuePaise), sum(i.cgstPaise),
                   sum(i.sgstPaise), sum(i.igstPaise), sum(i.cessPaise)
            from CreditNoteItem i
            where i.creditNote.noteDate between :from and :to
            group by i.hsnCode, i.unit
            """)
    List<Object[]> taxByHsn(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
