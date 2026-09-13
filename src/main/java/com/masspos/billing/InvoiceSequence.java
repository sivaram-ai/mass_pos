package com.masspos.billing;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

/**
 * Counter behind gapless invoice numbers, one row per terminal per financial year. It is advanced
 * in the same SQLite transaction that inserts the invoice: a rolled-back sale gives its number
 * back, a committed one can never be reused. A DB trigger rejects any move backwards.
 *
 * <p>Gapless only holds because SQLite has a single writer and transactions begin IMMEDIATE, so
 * two sales on this terminal can never read the same value.
 */
@Entity
@Audited
@Table(name = "invoice_sequence", uniqueConstraints = @UniqueConstraint(
        name = "uk_invoice_sequence_terminal_fy", columnNames = {"terminal_code", "financial_year"}))
public class InvoiceSequence extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    @Column(nullable = false, updatable = false, length = 4)
    private String financialYear;

    @Column(nullable = false)
    private long lastIssued;

    protected InvoiceSequence() {
    }

    public InvoiceSequence(String terminalCode, String financialYear) {
        this.terminalCode = terminalCode;
        this.financialYear = financialYear;
    }

    /** Reserves the next number. Only call inside the transaction that persists the invoice. */
    public long next() {
        return ++lastIssued;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public String getFinancialYear() {
        return financialYear;
    }

    public long getLastIssued() {
        return lastIssued;
    }
}
