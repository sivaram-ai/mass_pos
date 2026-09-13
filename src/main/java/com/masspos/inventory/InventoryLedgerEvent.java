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

    protected InventoryLedgerEvent() {
    }

    public InventoryLedgerEvent(Product product, LedgerEventType type, long quantityDeltaMilli, UUID referenceId,
                                String terminalCode, Instant occurredAt, User recordedBy, String note) {
        type.checkDelta(quantityDeltaMilli);
        this.product = product;
        this.type = type;
        this.quantityDeltaMilli = quantityDeltaMilli;
        this.referenceId = referenceId;
        this.terminalCode = terminalCode;
        this.occurredAt = occurredAt;
        this.recordedBy = recordedBy;
        this.note = note;
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

    public String getNote() {
        return note;
    }
}
