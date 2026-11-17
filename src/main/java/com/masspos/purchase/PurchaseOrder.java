package com.masspos.purchase;

import com.masspos.common.persistence.BaseEntity;
import com.masspos.user.User;
import com.masspos.vendor.Vendor;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * An order placed with a vendor: what the shop wants, from whom, and whether anybody has agreed
 * to it yet.
 *
 * <p>The point of it is the gap between asking and buying. A manager writes down what the kitchen
 * needs; an administrator agrees to the money; the goods turn up and only then do the counts move.
 * Before this, a delivery was the first anybody heard of a purchase.
 *
 * <p>Not an invoice and not append-only: an order is a plan, and plans change until they are
 * agreed to. What it must never do is change after that, which is why approving it, rejecting it
 * and receiving it are the only things that may touch an order that is no longer pending, and why
 * its lines are {@code updatable = false} in everything but what actually arrived.
 */
@Entity
@Audited
@Table(name = "purchase_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_purchase_order_number", columnNames = "number"),
        indexes = @Index(name = "ix_purchase_order_vendor", columnList = "vendor_id"))
public class PurchaseOrder extends BaseEntity {

    @Size(max = 16)
    @Column(nullable = false, length = 16, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false, updatable = false)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status = PurchaseOrderStatus.PENDING_APPROVAL;

    @Column(nullable = false, updatable = false)
    private LocalDate orderedOn;

    /** When the shop expects it, which is what a manager chases on. Optional. */
    private LocalDate expectedOn;

    @Size(max = 200)
    @Column(length = 200, updatable = false)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    private Instant decidedAt;

    /** Why it was turned down, in the words of whoever turned it down. */
    @Size(max = 200)
    @Column(length = 200)
    private String decision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by_id")
    private User receivedBy;

    private Instant receivedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<PurchaseOrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(String number, Vendor vendor, LocalDate orderedOn, LocalDate expectedOn, String note,
                         User createdBy) {
        this.number = number;
        this.vendor = vendor;
        this.orderedOn = orderedOn;
        this.expectedOn = expectedOn;
        this.note = note;
        this.createdBy = createdBy;
    }

    public void add(PurchaseOrderLine line) {
        line.placedOn(this);
        lines.add(line);
    }

    /** An administrator agrees to the money. Only then can goods be received against it. */
    public void approve(User administrator, Instant when) {
        requirePending("approved");
        this.status = PurchaseOrderStatus.APPROVED;
        this.decidedBy = administrator;
        this.decidedAt = when;
    }

    public void reject(User administrator, Instant when, String why) {
        requirePending("rejected");
        this.status = PurchaseOrderStatus.REJECTED;
        this.decidedBy = administrator;
        this.decidedAt = when;
        this.decision = why;
    }

    public void cancel(User by, Instant when, String why) {
        if (status == PurchaseOrderStatus.RECEIVED) {
            throw new IllegalStateException("The goods for " + number + " have already come in,"
                    + " so the order cannot be called off. Send them back instead.");
        }
        this.status = PurchaseOrderStatus.CANCELLED;
        this.decidedBy = by;
        this.decidedAt = when;
        this.decision = why;
    }

    /** The goods turned up. The counts move elsewhere; this records that they did. */
    public void receive(User by, Instant when) {
        if (status != PurchaseOrderStatus.APPROVED) {
            throw new IllegalStateException(status == PurchaseOrderStatus.PENDING_APPROVAL
                    ? "Order " + number + " has not been approved yet, so goods cannot be taken in against it."
                    : "Order " + number + " is " + status.name().toLowerCase().replace('_', ' ')
                      + ", so goods cannot be taken in against it.");
        }
        this.status = PurchaseOrderStatus.RECEIVED;
        this.receivedBy = by;
        this.receivedAt = when;
    }

    private void requirePending(String what) {
        if (status != PurchaseOrderStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Order " + number + " has already been decided, so it cannot be "
                    + what + " now.");
        }
    }

    /** What the order is expected to come to, at the costs written on it. */
    public long getValuePaise() {
        return lines.stream().mapToLong(PurchaseOrderLine::getValuePaise).sum();
    }

    public String getNumber() {
        return number;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public LocalDate getOrderedOn() {
        return orderedOn;
    }

    public LocalDate getExpectedOn() {
        return expectedOn;
    }

    public String getNote() {
        return note == null ? "" : note;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public User getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecision() {
        return decision == null ? "" : decision;
    }

    public User getReceivedBy() {
        return receivedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public List<PurchaseOrderLine> getLines() {
        return lines;
    }
}
