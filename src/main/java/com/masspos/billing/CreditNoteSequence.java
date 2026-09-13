package com.masspos.billing;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

/**
 * Counter behind gapless credit note numbers, one row per terminal per financial year. Rule 53 wants
 * credit notes in a series of their own, so this runs apart from {@link InvoiceSequence}, under the
 * same rules: advanced in the transaction that inserts the note, and never backwards.
 */
@Entity
@Audited
@Table(name = "credit_note_sequence", uniqueConstraints = @UniqueConstraint(
        name = "uk_credit_note_sequence_terminal_fy", columnNames = {"terminal_code", "financial_year"}))
public class CreditNoteSequence extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    @Column(nullable = false, updatable = false, length = 4)
    private String financialYear;

    @Column(nullable = false)
    private long lastIssued;

    protected CreditNoteSequence() {
    }

    public CreditNoteSequence(String terminalCode, String financialYear) {
        this.terminalCode = terminalCode;
        this.financialYear = financialYear;
    }

    /** Reserves the next number. Only call inside the transaction that persists the credit note. */
    public long next() {
        return ++lastIssued;
    }

    public long getLastIssued() {
        return lastIssued;
    }
}
