package com.masspos.billing;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Immutable;
import org.hibernate.envers.Audited;

/** Money paid back for a return: counted out of the drawer, or sent back by UPI or card. */
@Entity
@Immutable
@Audited
@Table(name = "credit_note_refund")
public class CreditNoteRefund extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_note_id", nullable = false, updatable = false)
    private CreditNote creditNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private PaymentMode mode;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Size(max = 60)
    @Column(length = 60, updatable = false)
    private String reference;

    protected CreditNoteRefund() {
    }

    public CreditNoteRefund(PaymentMode mode, long amountPaise, String reference) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("A refund must be for a positive amount");
        }
        this.mode = mode;
        this.amountPaise = amountPaise;
        this.reference = reference;
    }

    void attachTo(CreditNote creditNote) {
        this.creditNote = creditNote;
    }

    public CreditNote getCreditNote() {
        return creditNote;
    }

    public PaymentMode getMode() {
        return mode;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getReference() {
        return reference;
    }
}
