package com.masspos.inventory;

import com.masspos.catalog.Product;
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
 * One stock movement. Stock on hand is {@code SUM(quantity_delta_milli)} per product; no absolute
 * quantity is stored anywhere, so events recorded by terminals that were offline merge by set
 * union (deduplicated on id) instead of overwriting each other. Corrections are new compensating
 * events, never edits: the table is append-only in Hibernate ({@link Immutable}) and in SQLite.
 */
@Entity
@Immutable
@Audited
@Table(name = "inventory_ledger_event", indexes = {
        // Covering index: stock on hand is an index-only SUM with no table lookups.
        @Index(name = "ix_ledger_product_delta", columnList = "product_id, quantity_delta_milli"),
        @Index(name = "ix_ledger_reference", columnList = "reference_id")})
public class InventoryLedgerEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private LedgerEventType type;

    /** Signed change in milli-units: 2 pieces sold is -2000, 1.25 kg received is +1250. */
    @Column(nullable = false, updatable = false)
    private long quantityDeltaMilli;

    /** Source document: the invoice for SALE / SALE_CANCELLED, the goods receipt for PURCHASE_RECEIPT. */
    @Column(updatable = false)
    private UUID referenceId;

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    /** When the movement happened; can precede createdAt when entered after the fact. */
    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_id", updatable = false)
    private User recordedBy;

    @Size(max = 200)
    @Column(length = 200, updatable = false)
    private String note;

    /**
     * What the shop paid for one unit of what came in, in paise. Zero on everything that is not
     * goods arriving, and on goods that arrived before anybody was asked for a price.
     *
     * <p>Per unit rather than per delivery: it is the figure a shopkeeper knows and checks against
     * the next delivery, and the total is the quantity times it. Kept on the movement itself, so
     * what a delivery cost is still answerable years later whatever the item is priced at now.
     */
    @Column(nullable = false, updatable = false, columnDefinition = "bigint default 0")
    private long buyingPricePaise;

    /**
     * Who the goods came from, as it was typed at the time.
     *
     * <p>A name and not a reference, deliberately, and the same reasoning as the seller on an
     * invoice: this is a snapshot of what was true when the delivery arrived. A vendor renamed or
     * removed later must not rewrite the history of what the shop bought and from whom. When
     * vendors become records of their own, an event keeps the name it was recorded under.
     */
    @Size(max = 120)
    @Column(length = 120, nullable = false, updatable = false, columnDefinition = "varchar(120) default ''")
    private String vendorName = "";

    protected InventoryLedgerEvent() {
    }

    public InventoryLedgerEvent(Product product, LedgerEventType type, long quantityDeltaMilli, UUID referenceId,
                                String terminalCode, Instant occurredAt, User recordedBy, String note) {
        this(product, type, quantityDeltaMilli, referenceId, terminalCode, occurredAt, recordedBy, note, 0, "");
    }

    /** Goods arriving, which are the only movement anybody is asked a price or a vendor for. */
    public InventoryLedgerEvent(Product product, LedgerEventType type, long quantityDeltaMilli, UUID referenceId,
                                String terminalCode, Instant occurredAt, User recordedBy, String note,
                                long buyingPricePaise, String vendorName) {
        type.checkDelta(quantityDeltaMilli);
        this.product = product;
        this.type = type;
        this.quantityDeltaMilli = quantityDeltaMilli;
        this.referenceId = referenceId;
        this.terminalCode = terminalCode;
        this.occurredAt = occurredAt;
        this.recordedBy = recordedBy;
        this.note = note;
        this.buyingPricePaise = Math.max(0, buyingPricePaise);
        this.vendorName = vendorName == null ? "" : vendorName.trim();
    }

    public Product getProduct() {
        return product;
    }

    public LedgerEventType getType() {
        return type;
    }

    public long getQuantityDeltaMilli() {
        return quantityDeltaMilli;
    }

    public UUID getReferenceId() {
        return referenceId;
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

    public long getBuyingPricePaise() {
        return buyingPricePaise;
    }

    public String getVendorName() {
        return vendorName == null ? "" : vendorName;
    }

    public String getNote() {
        return note;
    }
}
