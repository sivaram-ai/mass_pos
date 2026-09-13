package com.masspos.billing;

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
 * A bill parked mid-sale so the counter can serve the next customer: the classic "customer forgot
 * the milk" case. It is not an invoice and consumes no invoice number; resuming it deletes the row
 * and the sale is numbered only when it is actually taken.
 */
@Entity
@Audited
@Table(name = "held_bill", indexes = @Index(name = "ix_held_bill_terminal", columnList = "terminal_code"))
public class HeldBill extends BaseEntity {

    @NotBlank
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String label;

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false, updatable = false)
    private User cashier;

    /** The cart as the billing screen holds it: lines, quantities, discounts, customer details. */
    @Lob
    @NotBlank
    @Column(nullable = false)
    private String cartJson;

    /** Shown in the resume list so the cashier can tell parked bills apart. */
    private long estimatedTotalPaise;

    protected HeldBill() {
    }

    public HeldBill(String label, String terminalCode, User cashier, String cartJson, long estimatedTotalPaise) {
        this.label = label;
        this.terminalCode = terminalCode;
        this.cashier = cashier;
        this.cartJson = cartJson;
        this.estimatedTotalPaise = estimatedTotalPaise;
    }

    public String getLabel() {
        return label;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public User getCashier() {
        return cashier;
    }

    public String getCartJson() {
        return cartJson;
    }

    public long getEstimatedTotalPaise() {
        return estimatedTotalPaise;
    }
}
