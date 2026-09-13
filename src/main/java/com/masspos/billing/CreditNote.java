package com.masspos.billing;

import com.masspos.common.persistence.BaseEntity;
import com.masspos.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.Immutable;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Goods taken back: a GST credit note (section 34, Rule 53), or for a shop without a GSTIN a plain
 * return slip. It puts the stock back and pays the customer back; the bill it answers, if any, is
 * left exactly as it was issued.
 *
 * <p>Numbered in its own gapless series per till and financial year ({@code T1-2627-R0001}), built
 * completely in memory and persisted once. Never changed or deleted afterwards: a mistaken return
 * is put right with a new bill.
 */
@Entity
@Immutable
@Audited
@Table(name = "credit_note",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_credit_note_number", columnNames = "credit_note_number"),
                @UniqueConstraint(name = "uk_credit_note_terminal_fy_seq",
                        columnNames = {"terminal_code", "financial_year", "sequence_no"})},
        indexes = @Index(name = "ix_credit_note_date", columnList = "note_date"))
public class CreditNote extends BaseEntity implements TaxDocument {

    @Column(nullable = false, updatable = false, length = InvoiceNumbers.MAX_LENGTH)
    private String creditNoteNumber;

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    @Column(nullable = false, updatable = false, length = 4)
    private String financialYear;

    @Column(nullable = false, updatable = false)
    private long sequenceNo;

    @NotNull
    @Column(nullable = false, updatable = false)
    private LocalDate noteDate;

    @NotNull
    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    @Valid
    @NotNull
    @Embedded
    private SellerDetails seller;

    /** The bill the goods were sold on; null for a return without the bill. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_invoice_id", updatable = false)
    private Invoice originalInvoice;

    @Column(updatable = false, length = InvoiceNumbers.MAX_LENGTH)
    private String originalInvoiceNumber;

    @Column(updatable = false)
    private LocalDate originalInvoiceDate;

    @ValidGstin
    @Column(updatable = false, length = 15)
    private String buyerGstin;

    @Size(max = 100)
    @Column(updatable = false, length = 100)
    private String buyerName;

    @NotNull
    @Pattern(regexp = "|\\d{2}")
    @Column(nullable = false, updatable = false, length = 2)
    private String placeOfSupply;

    @Size(max = 200)
    @Column(updatable = false, length = 200)
    private String reason;

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

    @Column(nullable = false, updatable = false)
    private long roundOffPaise;

    /** What the customer is paid back. */
    @Column(nullable = false, updatable = false)
    private long grandTotalPaise;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false, updatable = false)
    private User cashier;

    @OneToMany(mappedBy = "creditNote", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<CreditNoteItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "creditNote", cascade = CascadeType.PERSIST)
    private List<CreditNoteRefund> refunds = new ArrayList<>();

    protected CreditNote() {
    }

    /**
     * @param original the bill being answered, or null; its buyer and place of supply carry over
     */
    public CreditNote(String terminalCode, long sequenceNo, LocalDate noteDate, Instant issuedAt, SellerDetails seller,
                      Invoice original, String placeOfSupply, String buyerGstin, String buyerName, String reason,
                      User cashier) {
        this.terminalCode = terminalCode;
        this.financialYear = InvoiceNumbers.financialYearCode(noteDate);
        this.sequenceNo = sequenceNo;
        this.creditNoteNumber = InvoiceNumbers.formatCreditNote(terminalCode, financialYear, sequenceNo);
        this.noteDate = noteDate;
        this.issuedAt = issuedAt;
        this.seller = seller;
        this.originalInvoice = original;
        if (original != null) {
            this.originalInvoiceNumber = original.getInvoiceNumber();
            this.originalInvoiceDate = original.getInvoiceDate();
        }
        this.placeOfSupply = placeOfSupply;
        this.buyerGstin = buyerGstin;
        this.buyerName = buyerName;
        this.reason = reason;
        this.cashier = cashier;
    }

    public void addItem(CreditNoteItem item) {
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

    public void addRefund(CreditNoteRefund refund) {
        requireNotYetPersisted();
        refund.attachTo(this);
        refunds.add(refund);
    }

    public void applyRoundOff(long roundOffPaise) {
        requireNotYetPersisted();
        this.roundOffPaise = roundOffPaise;
        recomputeGrandTotal();
    }

    private void recomputeGrandTotal() {
        grandTotalPaise = taxableValuePaise + cgstPaise + sgstPaise + igstPaise + cessPaise + roundOffPaise;
    }

    private void requireNotYetPersisted() {
        if (getVersion() != null) {
            throw new IllegalStateException("Credit note " + creditNoteNumber + " is already issued");
        }
    }

    @Override
    public String getNumber() {
        return creditNoteNumber;
    }

    @Override
    public LocalDate getDocumentDate() {
        return noteDate;
    }

    @Override
    public List<CreditNoteItem> getLines() {
        return Collections.unmodifiableList(items);
    }

    public String getCreditNoteNumber() {
        return creditNoteNumber;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public LocalDate getNoteDate() {
        return noteDate;
    }

    @Override
    public Instant getIssuedAt() {
        return issuedAt;
    }

    @Override
    public SellerDetails getSeller() {
        return seller;
    }

    public Invoice getOriginalInvoice() {
        return originalInvoice;
    }

    public String getOriginalInvoiceNumber() {
        return originalInvoiceNumber;
    }

    public LocalDate getOriginalInvoiceDate() {
        return originalInvoiceDate;
    }

    @Override
    public String getBuyerGstin() {
        return buyerGstin;
    }

    @Override
    public String getBuyerName() {
        return buyerName;
    }

    @Override
    public String getPlaceOfSupply() {
        return placeOfSupply;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public long getTaxableValuePaise() {
        return taxableValuePaise;
    }

    @Override
    public long getCgstPaise() {
        return cgstPaise;
    }

    @Override
    public long getSgstPaise() {
        return sgstPaise;
    }

    @Override
    public long getIgstPaise() {
        return igstPaise;
    }

    @Override
    public long getCessPaise() {
        return cessPaise;
    }

    @Override
    public long getRoundOffPaise() {
        return roundOffPaise;
    }

    @Override
    public long getGrandTotalPaise() {
        return grandTotalPaise;
    }

    @Override
    public User getCashier() {
        return cashier;
    }

    public List<CreditNoteItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<CreditNoteRefund> getRefunds() {
        return Collections.unmodifiableList(refunds);
    }
}
