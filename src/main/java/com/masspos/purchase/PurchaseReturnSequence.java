package com.masspos.purchase;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

/**
 * Counter behind purchase return numbers, one row per financial year, advanced in the same
 * transaction that writes the return - as the order sequence is, and for the same reason.
 *
 * <p>Its own table rather than a share of the order counter: a shop telephoning its vendor about
 * PR-0007 should not have to explain that the orders and the returns take turns at the numbers.
 */
@Entity
@Audited
@Table(name = "purchase_return_sequence",
        uniqueConstraints = @UniqueConstraint(name = "uk_purchase_return_sequence_fy",
                columnNames = "financial_year"))
public class PurchaseReturnSequence extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 4)
    private String financialYear;

    @Column(nullable = false)
    private long lastIssued;

    protected PurchaseReturnSequence() {
    }

    public PurchaseReturnSequence(String financialYear) {
        this.financialYear = financialYear;
    }

    /** Reserves the next number. Only call inside the transaction that persists the return. */
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
