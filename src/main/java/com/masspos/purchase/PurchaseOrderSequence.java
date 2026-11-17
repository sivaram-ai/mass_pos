package com.masspos.purchase;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

/**
 * Counter behind purchase order numbers, one row per financial year, advanced in the same
 * transaction that writes the order - as invoice numbers are, and for the same reason: SQLite has
 * one writer and transactions begin IMMEDIATE, so two orders can never read the same value.
 *
 * <p>Unlike an invoice number this one carries no legal weight; it is a handle for a shopkeeper to
 * chase a delivery with. It is still worth being gapless, because "where is PO-0043" is a question
 * somebody asks down a telephone.
 */
@Entity
@Audited
@Table(name = "purchase_order_sequence",
        uniqueConstraints = @UniqueConstraint(name = "uk_purchase_order_sequence_fy", columnNames = "financial_year"))
public class PurchaseOrderSequence extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 4)
    private String financialYear;

    @Column(nullable = false)
    private long lastIssued;

    protected PurchaseOrderSequence() {
    }

    public PurchaseOrderSequence(String financialYear) {
        this.financialYear = financialYear;
    }

    /** Reserves the next number. Only call inside the transaction that persists the order. */
    public long next() {
        return ++lastIssued;
    }

    public String getFinancialYear() {
        return financialYear;
    }

    public long getLastIssued() {
        return lastIssued;
    }
}
