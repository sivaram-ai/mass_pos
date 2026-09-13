package com.masspos.billing;

import com.masspos.common.persistence.BaseEntity;
import com.masspos.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GST tax invoice (Rule 46), or a plain bill when the shop has no GSTIN ({@link #isTaxInvoice()}).
 * Built completely in memory, then persisted once at the moment of sale.
 * After that only {@link #cancel} may change it: the fiscal columns are {@code updatable = false}
 * here and frozen by a trigger in {@code AuditTrailGuard}, and rows are never deleted.
 *
 * <p>The seller is a {@link SellerDetails} snapshot of {@code pos.company} taken at issue, so a
 * reprint matches the original even after the store's settings change.
 */
@Entity
@Audited
@Table(name = "invoice",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_invoice_number", columnNames = "invoice_number"),
                @UniqueConstraint(name = "uk_invoice_terminal_fy_seq",
                        columnNames = {"terminal_code", "financial_year", "sequence_no"})},
        indexes = @Index(name = "ix_invoice_date", columnList = "invoice_date"))
public class Invoice extends BaseEntity {

    @Column(nullable = false, updatable = false, length = InvoiceNumbers.MAX_LENGTH)
    private String invoiceNumber;

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    @Column(nullable = false, updatable = false, length = 4)
    private String financialYear;

    @Column(nullable = false, updatable = false)
    private long sequenceNo;

    @NotNull
    @Column(nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @NotNull
    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvoiceStatus status = InvoiceStatus.ISSUED;

    @Valid
    @NotNull
    @Embedded
    private SellerDetails seller;

    /** Null for a B2C sale. */
    @ValidGstin
    @Column(updatable = false, length = 15)
    private String buyerGstin;

    @Size(max = 100)
    @Column(updatable = false, length = 100)
    private String buyerName;

    /**
     * State code of the place of supply. Differs from the seller's state ⇒ IGST instead of CGST + SGST.
     * Empty on the bill of a shop without a GSTIN, where no tax applies.
     */
    @NotNull
    @Pattern(regexp = "|\\d{2}")
    @Column(nullable = false, updatable = false, length = 2)
    private String placeOfSupply;

    @Column(nullable = false, updatable = false)
    private long taxableValuePaise;

    @Column(nullable = false, updatable = false)
    private long cgstPaise;

    @Column(nullable = false, updatable = false)
    private long sgstPaise;

    @Column(nullable = false, updatable = false)
    private long igstPaise;

    @Column(nullable = false, updatable = false)
    private long cessPaise;

    /** Signed adjustment to reach the payable amount, e.g. rounding to the nearest rupee. */
    @Column(nullable = false, updatable = false)
    private long roundOffPaise;

    @Column(nullable = false, updatable = false)
    private long grandTotalPaise;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false, updatable = false)
    private User cashier;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<InvoiceItem> items = new ArrayList<>();

    /** One row per tender; several make a split payment. */
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.PERSIST)
    private List<InvoicePayment> payments = new ArrayList<>();

    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_id")
    private User cancelledBy;

    @Size(max = 200)
    @Column(length = 200)
    private String cancelReason;

    protected Invoice() {
    }

    /**
     * @param sequenceNo from {@link InvoiceSequence#next()} for this terminal and financial year,
     *                   taken in the same transaction that persists this invoice
     * @param seller     {@link SellerDetails#from} the current {@code pos.company} settings
     */
    public Invoice(String terminalCode, long sequenceNo, LocalDate invoiceDate, Instant issuedAt, SellerDetails seller,
                   String placeOfSupply, String buyerGstin, String buyerName, User cashier) {
        this.terminalCode = terminalCode;
        this.financialYear = InvoiceNumbers.financialYearCode(invoiceDate);
        this.sequenceNo = sequenceNo;
        this.invoiceNumber = InvoiceNumbers.format(terminalCode, financialYear, sequenceNo);
        this.invoiceDate = invoiceDate;
        this.issuedAt = issuedAt;
        this.seller = seller;
        this.placeOfSupply = placeOfSupply;
        this.buyerGstin = buyerGstin;
        this.buyerName = buyerName;
        this.cashier = cashier;
    }

    /** Appends the next line and rolls its amounts into the header totals. */
    public void addItem(InvoiceItem item) {
        requireNotYetPersisted();
        item.attachTo(this, items.size() + 1);
        items.add(item);
        taxableValuePaise += item.getTaxableValuePaise();
        cgstPaise += item.getCgstPaise();
        sgstPaise += item.getSgstPaise();
        igstPaise += item.getIgstPaise();
        cessPaise += item.getCessPaise();
        recomputeGrandTotal();
    }

    /** Cash, card, UPI or a mix of them. The total must match the bill before it is persisted. */
    public void addPayment(InvoicePayment payment) {
        requireNotYetPersisted();
        payment.attachTo(this);
        payments.add(payment);
    }

    public void applyRoundOff(long roundOffPaise) {
        requireNotYetPersisted();
        this.roundOffPaise = roundOffPaise;
        recomputeGrandTotal();
    }

    /**
     * The only change allowed after issue. Returned stock is restored by compensating ledger
     * events, never by editing the sale.
     */
    public void cancel(User by, String reason, Instant at) {
        if (status == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Invoice " + invoiceNumber + " is already cancelled");
        }
        this.status = InvoiceStatus.CANCELLED;
        this.cancelledBy = by;
        this.cancelReason = reason;
        this.cancelledAt = at;
    }

    public boolean isInterState() {
        return seller.isGstRegistered() && !seller.getStateCode().equals(placeOfSupply);
    }

    public boolean isB2b() {
        return buyerGstin != null;
    }

    /** False for a shop without a GSTIN: its bill charges no tax and is not a GST tax invoice. */
    public boolean isTaxInvoice() {
        return seller.isGstRegistered();
    }

    private void recomputeGrandTotal() {
        grandTotalPaise = taxableValuePaise + cgstPaise + sgstPaise + igstPaise + cessPaise + roundOffPaise;
    }

    private void requireNotYetPersisted() {
        // Hibernate seeds @Version at persist(); header columns are not updatable after that, so a
        // late change would be dropped silently.
        if (getVersion() != null) {
            throw new IllegalStateException("Invoice " + invoiceNumber + " is already issued");
        }
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public String getFinancialYear() {
        return financialYear;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public SellerDetails getSeller() {
        return seller;
    }

    public String getBuyerGstin() {
        return buyerGstin;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public String getPlaceOfSupply() {
        return placeOfSupply;
    }

    public long getTaxableValuePaise() {
        return taxableValuePaise;
    }

    public long getCgstPaise() {
        return cgstPaise;
    }

    public long getSgstPaise() {
        return sgstPaise;
    }

    public long getIgstPaise() {
        return igstPaise;
    }

    public long getCessPaise() {
        return cessPaise;
    }

    public long getRoundOffPaise() {
        return roundOffPaise;
    }

    public long getGrandTotalPaise() {
        return grandTotalPaise;
    }

    public User getCashier() {
        return cashier;
    }

    public List<InvoiceItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<InvoicePayment> getPayments() {
        return Collections.unmodifiableList(payments);
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public User getCancelledBy() {
        return cancelledBy;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
