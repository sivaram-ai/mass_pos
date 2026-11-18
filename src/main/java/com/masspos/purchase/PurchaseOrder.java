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

    /**
     * Who it is going to, where that is somebody on the shop's list.
     *
     * <p>Null is ordinary. A shop buys from a man off a lorry, from the hardware shop on the
     * corner, from somebody it will never buy from again - and making it write all of them down
     * as vendors before it can write an order is how shops end up with a vendor list of
     * one-offs and an order nobody wrote at all.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", updatable = false)
    private Vendor vendor;

    /**
     * Who it is going to, in words, as it was written. The vendor's own name where there is a
     * vendor - a snapshot, like the seller on an invoice, so renaming him next year does not
     * rewrite this year's orders - and whatever was typed where there is not.
     */
    @Size(max = 120)
    @Column(name = "vendor_name", length = 120, nullable = false, updatable = false,
            columnDefinition = "varchar(120) default ''")
    private String vendorName = "";

    /**
     * The order this one was written to replace, by number. An order is edited the way a bill is:
     * the old one is cancelled and a new one takes its place, so what was agreed to and what was
     * ordered can never quietly differ.
     */
    @Size(max = 16)
    @Column(name = "replaces_order_number", length = 16, nullable = false, updatable = false,
            columnDefinition = "varchar(16) default ''")
    private String replacesOrderNumber = "";

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

    public PurchaseOrder(String number, Vendor vendor, String vendorName, LocalDate orderedOn,
                         LocalDate expectedOn, String note, User createdBy, String replacesOrderNumber) {
        this.number = number;
        this.vendor = vendor;
        this.vendorName = vendorName == null ? "" : vendorName.trim();
        this.orderedOn = orderedOn;
        this.expectedOn = expectedOn;
        this.note = note;
        this.createdBy = createdBy;
        this.replacesOrderNumber = replacesOrderNumber == null ? "" : replacesOrderNumber;
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

    /**
     * Edited: this one is cancelled and the number of the one taking its place is written on it,
     * exactly as an edited bill is. An order whose goods have arrived cannot be edited - the
     * counts have moved - and neither can one already decided against.
     */
    public void replacedBy(User by, Instant when, String newNumber) {
        if (status == PurchaseOrderStatus.RECEIVED) {
            throw new IllegalStateException("The goods on order " + number + " have already come in, so it "
                    + "cannot be changed now. Send back what should not have come.");
        }
        if (status == PurchaseOrderStatus.REJECTED || status == PurchaseOrderStatus.CANCELLED) {
            throw new IllegalStateException("Order " + number + " is "
                    + status.name().toLowerCase().replace('_', ' ') + ", so there is nothing to change.");
        }
        this.status = PurchaseOrderStatus.CANCELLED;
        this.decidedBy = by;
        this.decidedAt = when;
        this.decision = "Edited, replaced by " + newNumber;
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

    /**
     * What actually arrived came to. The figure a payment is measured against once an order has
     * been received: a lorry that brought eight of the twelve is owed for eight.
     */
    public long getReceivedValuePaise() {
        return lines.stream().mapToLong(PurchaseOrderLine::getReceivedValuePaise).sum();
    }

    public String getNumber() {
        return number;
    }

    public Vendor getVendor() {
        return vendor;
    }

    /** Who it is going to, in words: the vendor's name, what was typed, or nothing at all. */
    public String getVendorName() {
        if (vendorName != null && !vendorName.isEmpty()) {
            return vendorName;
        }
        return vendor == null ? "" : vendor.getName();
    }

    /** The order this one replaced, or empty where it is the first of its line. */
    public String getReplacesOrderNumber() {
        return replacesOrderNumber == null ? "" : replacesOrderNumber;
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
