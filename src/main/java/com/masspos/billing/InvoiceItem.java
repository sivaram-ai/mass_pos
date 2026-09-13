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
 * One invoice line. Amounts are computed by the billing calculator (GST rounding rules live there)
 * and stored as issued; this entity only checks that they add up. Product details are copied at
 * the time of sale so later catalogue edits never alter an issued invoice.
 */
@Entity
@Immutable
@Audited
@Table(name = "invoice_item", uniqueConstraints = @UniqueConstraint(
        name = "uk_invoice_item_line", columnNames = {"invoice_id", "line_no"}))
public class InvoiceItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @Column(nullable = false, updatable = false)
    private int lineNo;

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

    /** Rate actually applied, which can differ from the product default (e.g. price-based apparel slabs). */
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

    protected InvoiceItem() {
    }

    public InvoiceItem(Product product, long quantityMilli, long unitPricePaise, long discountPaise,
                       long taxableValuePaise, int gstRateBp, int cessRateBp,
                       long cgstPaise, long sgstPaise, long igstPaise, long cessPaise) {
        if (quantityMilli <= 0) {
            throw new IllegalArgumentException("Quantity must be positive; returns are credit notes");
        }
        if (product.getUnit().isWholeUnitsOnly() && quantityMilli % 1000 != 0) {
            throw new IllegalArgumentException(product.getUnit() + " is sold in whole units only: " + quantityMilli);
        }
        if (unitPricePaise < 0 || discountPaise < 0 || taxableValuePaise < 0
                || cgstPaise < 0 || sgstPaise < 0 || igstPaise < 0 || cessPaise < 0) {
            throw new IllegalArgumentException("Invoice line amounts cannot be negative");
        }
        this.product = product;
        this.sku = product.getSku();
        this.productName = product.getName();
        this.hsnCode = product.getHsnCode();
        this.unit = product.getUnit();
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

    void attachTo(Invoice invoice, int lineNo) {
        this.invoice = invoice;
        this.lineNo = lineNo;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public int getLineNo() {
        return lineNo;
    }

    public Product getProduct() {
        return product;
    }

    public String getSku() {
        return sku;
    }

    public String getProductName() {
        return productName;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public UnitOfMeasure getUnit() {
        return unit;
    }

    public long getQuantityMilli() {
        return quantityMilli;
    }

    public long getUnitPricePaise() {
        return unitPricePaise;
    }

    public long getDiscountPaise() {
        return discountPaise;
    }

    public long getTaxableValuePaise() {
        return taxableValuePaise;
    }

    public int getGstRateBp() {
        return gstRateBp;
    }

    public int getCessRateBp() {
        return cessRateBp;
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

    public long getLineTotalPaise() {
        return lineTotalPaise;
    }
}
