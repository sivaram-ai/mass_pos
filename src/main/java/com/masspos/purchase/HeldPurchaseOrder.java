package com.masspos.purchase;

import com.masspos.common.persistence.BaseEntity;
import com.masspos.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

/**
 * An order put aside half written, so the manager can go and look at what the shelf actually holds
 * before finishing it.
 *
 * <p>The same thing a held bill is, for the same reason: somebody is interrupted. It takes no
 * order number and nothing is ordered; resuming it deletes the row, and the order is numbered only
 * when it is actually written.
 *
 * <p>A table of its own rather than a kind on {@code held_bill}: SQLite takes a new table on an
 * upgrade and will not alter a CHECK constraint on an old one, and the two have nothing to say to
 * each other beyond both being a screen somebody walked away from.
 */
@Entity
@Audited
@Table(name = "held_purchase_order",
        indexes = @Index(name = "ix_held_order_terminal", columnList = "terminal_code"))
public class HeldPurchaseOrder extends BaseEntity {

    @NotBlank
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String label;

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "written_by_id", nullable = false, updatable = false)
    private User writtenBy;

    /** The order as the screen holds it: the vendor, the date, the note and every line. */
    @Lob
    @NotBlank
    @Column(nullable = false)
    private String orderJson;

    /** Shown in the list, so one held order can be told from another. */
    private long estimatedTotalPaise;

    protected HeldPurchaseOrder() {
    }

    public HeldPurchaseOrder(String label, String terminalCode, User writtenBy, String orderJson,
                             long estimatedTotalPaise) {
        this.label = label;
        this.terminalCode = terminalCode;
        this.writtenBy = writtenBy;
        this.orderJson = orderJson;
        this.estimatedTotalPaise = estimatedTotalPaise;
    }

    public String getLabel() {
        return label;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public User getWrittenBy() {
        return writtenBy;
    }

    public String getOrderJson() {
        return orderJson;
    }

    public long getEstimatedTotalPaise() {
        return estimatedTotalPaise;
    }
}
