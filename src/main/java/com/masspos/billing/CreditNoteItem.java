package com.masspos.billing;

import com.masspos.catalog.Product;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;
import org.hibernate.envers.Audited;

/**
 * One item taken back. Amounts are positive, as on the credit note itself: the note as a whole is
 * what reduces the takings and the tax. Product details are copied, as on an invoice line.
 */
@Entity
@Immutable
@Audited
@Table(name = "credit_note_item", uniqueConstraints = @UniqueConstraint(
        name = "uk_credit_note_item_line", columnNames = {"credit_note_id", "line_no"}))
public class CreditNoteItem extends BaseEntity implements TaxLine {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_note_id", nullable = false, updatable = false)
    private CreditNote creditNote;

    @Column(nullable = false, updatable = false)
    private int lineNo;

    /** The line of the original bill this item came from; null for a return without a bill. */
    @Column(updatable = false)
    private Integer originalLineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false, updatable = false, length = 40)
    private String sku;

    @Column(nullable = false, updatable = false, length = 200)
    private String productName;

    @Column(nullable = false, updatable = false, length = 8)
    private String hsnCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private UnitOfMeasure unit;

    @Column(nullable = false, updatable = false)
    private long quantityMilli;

    @Column(nullable = false, updatable = false)
    private long unitPricePaise;

    @Column(nullable = false, updatable = false)
    private long discountPaise;

    @Column(nullable = false, updatable = false)
    private long taxableValuePaise;

    @Column(nullable = false, updatable = false)
    private int gstRateBp;

    @Column(nullable = false, updatable = false)
    private int cessRateBp;

    @Column(nullable = false, updatable = false)
    private long cgstPaise;

    @Column(nullable = false, updatable = false)
    private long sgstPaise;

    @Column(nullable = false, updatable = false)
    private long igstPaise;

    @Column(nullable = false, updatable = false)
    private long cessPaise;

    @Column(nullable = false, updatable = false)
    private long lineTotalPaise;

    protected CreditNoteItem() {
    }

    /** Copies the item's name, code and HSN from the bill line it came back from, else from the catalogue. */
    public CreditNoteItem(Product product, TaxLine originalLine, long quantityMilli, long unitPricePaise,
                          long discountPaise, long taxableValuePaise, int gstRateBp, int cessRateBp,
                          long cgstPaise, long sgstPaise, long igstPaise, long cessPaise) {
        if (quantityMilli <= 0) {
            throw new IllegalArgumentException("A returned quantity must be positive");
        }
        if (product.getUnit().isWholeUnitsOnly() && quantityMilli % 1000 != 0) {
            throw new IllegalArgumentException(product.getName() + " is returned in whole " + product.getUnit() + " only");
        }
        if (unitPricePaise < 0 || discountPaise < 0 || taxableValuePaise < 0
                || cgstPaise < 0 || sgstPaise < 0 || igstPaise < 0 || cessPaise < 0) {
            throw new IllegalArgumentException("Credit note amounts cannot be negative");
        }
        this.product = product;
        this.originalLineNo = originalLine == null ? null : originalLine.getLineNo();
        this.sku = originalLine == null ? product.getSku() : originalLine.getSku();
        this.productName = originalLine == null ? product.getName() : originalLine.getProductName();
        this.hsnCode = originalLine == null ? product.getHsnCode() : originalLine.getHsnCode();
        this.unit = originalLine == null ? product.getUnit() : originalLine.getUnit();
        this.quantityMilli = quantityMilli;
        this.unitPricePaise = unitPricePaise;
        this.discountPaise = discountPaise;
        this.taxableValuePaise = taxableValuePaise;
        this.gstRateBp = gstRateBp;
        this.cessRateBp = cessRateBp;
        this.cgstPaise = cgstPaise;
        this.sgstPaise = sgstPaise;
        this.igstPaise = igstPaise;
        this.cessPaise = cessPaise;
        this.lineTotalPaise = taxableValuePaise + cgstPaise + sgstPaise + igstPaise + cessPaise;
    }

    void attachTo(CreditNote creditNote, int lineNo) {
        this.creditNote = creditNote;
        this.lineNo = lineNo;
    }

    public CreditNote getCreditNote() {
        return creditNote;
    }

    @Override
    public int getLineNo() {
        return lineNo;
    }

    public Integer getOriginalLineNo() {
        return originalLineNo;
    }

    @Override
    public Product getProduct() {
        return product;
    }

    @Override
    public String getSku() {
        return sku;
    }

    @Override
    public String getProductName() {
        return productName;
    }

    @Override
    public String getHsnCode() {
        return hsnCode;
    }

    @Override
    public UnitOfMeasure getUnit() {
        return unit;
    }

    @Override
    public long getQuantityMilli() {
        return quantityMilli;
    }

    @Override
    public long getUnitPricePaise() {
        return unitPricePaise;
    }

    @Override
    public long getDiscountPaise() {
        return discountPaise;
    }

    @Override
    public int getGstRateBp() {
        return gstRateBp;
    }

    public int getCessRateBp() {
        return cessRateBp;
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
    public long getLineTotalPaise() {
        return lineTotalPaise;
    }
}
