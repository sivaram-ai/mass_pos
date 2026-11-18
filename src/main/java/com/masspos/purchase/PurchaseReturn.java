package com.masspos.purchase;

import com.masspos.common.persistence.BaseEntity;
import com.masspos.user.User;
import com.masspos.vendor.Vendor;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Goods going back to the vendor: the wrong thing came, or it came spoiled, or there was too much
 * of it.
 *
 * <p>The other end of a customer's return, and written for the same reason that one is written: a
 * shop that simply took the stock off its own count would have no paper for the argument about
 * who owes whom. The stock comes off the moment this is written - the goods are on the lorry -
 * and what the shop owes that vendor drops by what went back, because the two sums come off the
 * same ledgers.
 *
 * <p>No money changes hands here on purpose. A vendor settles a return against the next delivery
 * far more often than he hands cash back across a counter, and a shop that is genuinely given the
 * money records that as a payment, the other way round, where an administrator can see it.
 */
@Entity
@Audited
@Table(name = "purchase_return",
        uniqueConstraints = @UniqueConstraint(name = "uk_purchase_return_number", columnNames = "number"),
        indexes = @Index(name = "ix_purchase_return_vendor", columnList = "vendor_id"))
public class PurchaseReturn extends BaseEntity {

    @Size(max = 16)
    @Column(nullable = false, length = 16, updatable = false)
    private String number;

    /** Who they are going back to, where that is somebody on the list. Null is ordinary. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", updatable = false)
    private Vendor vendor;

    /** And in words, as it was written: the vendor's name, or whatever was typed. */
    @Size(max = 120)
    @Column(name = "vendor_name", length = 120, nullable = false, updatable = false,
            columnDefinition = "varchar(120) default ''")
    private String vendorName = "";

    /**
     * The order the goods came in on, where anybody knows which one it was. Null is ordinary: a
     * shop sending back a spoiled sack three weeks later is not going to go and find the order.
     */
    @Column(name = "against_order_id", updatable = false)
    private UUID againstOrderId;

    @Size(max = 16)
    @Column(name = "against_order_number", length = 16, nullable = false, updatable = false,
            columnDefinition = "varchar(16) default ''")
    private String againstOrderNumber = "";

    @Column(nullable = false, updatable = false)
    private LocalDate returnedOn;

    /** Why it went back, in the words of whoever sent it. */
    @Size(max = 200)
    @Column(length = 200, updatable = false)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", updatable = false)
    private User createdBy;

    @OneToMany(mappedBy = "sentBack", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<PurchaseReturnLine> lines = new ArrayList<>();

    protected PurchaseReturn() {
    }

    public PurchaseReturn(String number, Vendor vendor, String vendorName, UUID againstOrderId,
                          String againstOrderNumber, LocalDate returnedOn, String reason, User createdBy) {
        this.number = number;
        this.vendor = vendor;
        this.vendorName = vendorName == null ? "" : vendorName.trim();
        this.againstOrderId = againstOrderId;
        this.againstOrderNumber = againstOrderNumber == null ? "" : againstOrderNumber;
        this.returnedOn = returnedOn;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public void add(PurchaseReturnLine line) {
        line.sentBackOn(this);
        lines.add(line);
    }

    /** What went back came to, which is what comes off the vendor's balance. */
    public long getValuePaise() {
        return lines.stream().mapToLong(PurchaseReturnLine::getValuePaise).sum();
    }

    public String getNumber() {
        return number;
    }

    public Vendor getVendor() {
        return vendor;
    }

    /** Who they went back to, in words: the vendor's name, what was typed, or nothing. */
    public String getVendorName() {
        if (vendorName != null && !vendorName.isEmpty()) {
            return vendorName;
        }
        return vendor == null ? "" : vendor.getName();
    }

    public UUID getAgainstOrderId() {
        return againstOrderId;
    }

    public String getAgainstOrderNumber() {
        return againstOrderNumber == null ? "" : againstOrderNumber;
    }

    public LocalDate getReturnedOn() {
        return returnedOn;
    }

    public String getReason() {
        return reason == null ? "" : reason;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public List<PurchaseReturnLine> getLines() {
        return lines;
    }
}
