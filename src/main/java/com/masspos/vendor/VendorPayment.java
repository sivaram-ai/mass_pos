package com.masspos.vendor;

import com.masspos.common.persistence.BaseEntity;
import com.masspos.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Money paid to a vendor, and whether an administrator has agreed to it yet.
 *
 * <p>Recorded by whoever handed the money over and counted against the vendor's balance only once
 * it is approved. Until then it is on the screen marked as waiting, which is the honest state: the
 * shop knows somebody says they paid, and the books do not move on one person's say-so.
 *
 * <p>Not immutable, unlike the ledgers: a payment is approved after it is written, so the row has
 * to change once. What it may never do is change its amount or its vendor afterwards - those are
 * {@code updatable = false}, so the thing approved is the thing that was recorded.
 */
@Entity
@Audited
@Table(name = "vendor_payment", indexes = @Index(name = "ix_vendor_payment_vendor", columnList = "vendor_id"))
public class VendorPayment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false, updatable = false)
    private Vendor vendor;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private LocalDate paidOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private VendorPaymentMode mode;

    /** Cheque number, UPI reference, whatever the shop can find it by again. */
    @Size(max = 60)
    @Column(length = 60, nullable = false, updatable = false)
    private String reference = "";

    @Size(max = 200)
    @Column(length = 200, updatable = false)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_id", updatable = false)
    private User recordedBy;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    /**
     * The purchase order this money was against, where it was against one.
     *
     * <p>A bare id and not a mapping, because the purchase package already reads this one and a
     * reference back would be a cycle between the two for the sake of one column. Nullable and
     * always will be: a shop paying off a running account rather than an invoice is ordinary, and
     * so is one paying for a delivery taken before orders existed. It is what lets an order say
     * whether it has been paid for, which cannot be worked out from a vendor's running balance.
     */
    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(nullable = false)
    private boolean approved;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    private Instant approvedAt;

    protected VendorPayment() {
    }

    public VendorPayment(Vendor vendor, long amountPaise, LocalDate paidOn, VendorPaymentMode mode,
                         String reference, String note, User recordedBy, Instant recordedAt,
                         UUID purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
        this.vendor = vendor;
        this.amountPaise = amountPaise;
        this.paidOn = paidOn;
        this.mode = mode;
        this.reference = reference == null ? "" : reference.trim();
        this.note = note;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
    }

    /**
     * The order it was paid for was edited, so the money follows the order under its new number -
     * the way the amounts paid on a bill carry over to the bill that replaces it. The amount, the
     * vendor and who recorded it do not move: the thing approved is still the thing recorded.
     */
    public void nowAgainst(UUID purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    /** An administrator agrees to it, and only then does the vendor's balance move. */
    public void approve(User administrator, Instant when) {
        this.approved = true;
        this.approvedBy = administrator;
        this.approvedAt = when;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public VendorPaymentMode getMode() {
        return mode;
    }

    public String getReference() {
        return reference == null ? "" : reference;
    }

    public String getNote() {
        return note == null ? "" : note;
    }

    public User getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    /** The order this was against, or null where it was against the running account. */
    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public boolean isApproved() {
        return approved;
    }

    public User getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
