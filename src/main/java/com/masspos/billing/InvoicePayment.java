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

/**
 * One tender against a bill. Several rows make a split payment (part cash, part UPI). Cash also
 * records what the customer handed over, which is what the drawer is counted against at closing.
 */
@Entity
@Immutable
@Audited
@Table(name = "invoice_payment")
public class InvoicePayment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private PaymentMode mode;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    /** Cash handed over; equals the amount for every other mode. Change given is the difference. */
    @Column(nullable = false, updatable = false)
    private long tenderedPaise;

    /** UPI reference, card approval code, voucher number. */
    @Size(max = 60)
    @Column(length = 60, updatable = false)
    private String reference;

    protected InvoicePayment() {
    }

    public InvoicePayment(PaymentMode mode, long amountPaise, long tenderedPaise, String reference) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("A payment must be for a positive amount");
        }
        if (tenderedPaise < amountPaise) {
            throw new IllegalArgumentException("Tendered cannot be less than the amount paid");
        }
        if (mode != PaymentMode.CASH && tenderedPaise != amountPaise) {
            throw new IllegalArgumentException("Only cash can be tendered over the amount");
        }
        this.mode = mode;
        this.amountPaise = amountPaise;
        this.tenderedPaise = tenderedPaise;
        this.reference = reference;
    }

    void attachTo(Invoice invoice) {
        this.invoice = invoice;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public PaymentMode getMode() {
        return mode;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public long getTenderedPaise() {
        return tenderedPaise;
    }

    public String getReference() {
        return reference;
    }
}
