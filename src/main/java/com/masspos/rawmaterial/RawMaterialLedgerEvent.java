package com.masspos.rawmaterial;

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
import org.hibernate.annotations.Immutable;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement in the store: a sack bought, a tin used, a crate thrown out, a count corrected.
 *
 * <p>Its own table rather than a second kind of row in {@code inventory_ledger_event}. That table's
 * {@code product_id} is NOT NULL with a foreign key to the catalogue, and it is append-only on
 * every till already in a shop - making the column nullable would be an ALTER on a populated table,
 * which is the one thing SQLite will not do. A new table it takes without complaint.
 *
 * <p>Append-only in Hibernate ({@link Immutable}) and in SQLite ({@code AuditTrailGuard}), like the
 * stock ledger: what is in the store is the sum of these rows, and a correction is a new
 * compensating row rather than an edit of an old one.
 */
@Entity
@Immutable
@Audited
@Table(name = "raw_material_ledger_event", indexes = {
        // Covering index: what is in the store is an index-only SUM with no table lookups.
        @Index(name = "ix_raw_ledger_material_delta", columnList = "raw_material_id, quantity_delta_milli")})
public class RawMaterialLedgerEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raw_material_id", nullable = false, updatable = false)
    private RawMaterial rawMaterial;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private RawMaterialMovement type;

    /** Signed change in milli-units: 2 kg used is -2000, a 5 kg sack bought is +5000. */
    @Column(nullable = false, updatable = false)
    private long quantityDeltaMilli;

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_id", updatable = false)
    private User recordedBy;

    @Size(max = 200)
    @Column(length = 200, updatable = false)
    private String note;

    /** What one unit cost, on a purchase. Zero on everything else. See the stock ledger. */
    @Column(nullable = false, updatable = false)
    private long buyingPricePaise;

    /** Who it came from, as it was typed at the time: a snapshot, not a reference. */
    @Size(max = 120)
    @Column(length = 120, nullable = false, updatable = false)
    private String vendorName = "";

    /** The vendor on the shop's list, where it was one of them. Null is ordinary; see the shelf. */
    @Column(name = "vendor_id", updatable = false)
    private UUID vendorId;

    /**
     * The purchase order the goods came in against, by its number. Empty on everything that is not
     * a delivery. The shelf's ledger carries the same column, for the same reason.
     */
    @Size(max = 16)
    @Column(name = "order_number", length = 16, nullable = false, updatable = false,
            columnDefinition = "varchar(16) default ''")
    private String orderNumber = "";

    protected RawMaterialLedgerEvent() {
    }

    public RawMaterialLedgerEvent(RawMaterial rawMaterial, RawMaterialMovement type, long quantityDeltaMilli,
                                  String terminalCode, Instant occurredAt, User recordedBy, String note,
                                  long buyingPricePaise, String vendorName, UUID vendorId, String orderNumber) {
        type.checkDelta(quantityDeltaMilli);
        this.vendorId = vendorId;
        this.orderNumber = orderNumber == null ? "" : orderNumber.trim();
        this.rawMaterial = rawMaterial;
        this.type = type;
        this.quantityDeltaMilli = quantityDeltaMilli;
        this.terminalCode = terminalCode;
        this.occurredAt = occurredAt;
        this.recordedBy = recordedBy;
        this.note = note;
        this.buyingPricePaise = Math.max(0, buyingPricePaise);
        this.vendorName = vendorName == null ? "" : vendorName.trim();
    }

    public RawMaterial getRawMaterial() {
        return rawMaterial;
    }

    public RawMaterialMovement getType() {
        return type;
    }

    public long getQuantityDeltaMilli() {
        return quantityDeltaMilli;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public User getRecordedBy() {
        return recordedBy;
    }

    public String getNote() {
        return note == null ? "" : note;
    }

    public long getBuyingPricePaise() {
        return buyingPricePaise;
    }

    public String getVendorName() {
        return vendorName == null ? "" : vendorName;
    }

    /** The order these came in against, or empty where there was not one. */
    public String getOrderNumber() {
        return orderNumber == null ? "" : orderNumber;
    }

    public UUID getVendorId() {
        return vendorId;
    }
}
