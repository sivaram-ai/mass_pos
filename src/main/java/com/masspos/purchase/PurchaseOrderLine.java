package com.masspos.purchase;

import com.masspos.catalog.Product;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.common.persistence.BaseEntity;
import com.masspos.rawmaterial.RawMaterial;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

/**
 * One thing on an order: how much of it, and what it is expected to cost.
 *
 * <p>It points at a product <em>or</em> a raw material, never both and never neither. Two nullable
 * keys rather than a loose id with a kind beside it, so the database still refuses a line pointing
 * at something that is not there - the shelf and the store are different tables, and an order that
 * mixes bottled water and onions is an ordinary order.
 */
@Entity
@Audited
@Table(name = "purchase_order_line")
public class PurchaseOrderLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_material_id", updatable = false)
    private RawMaterial rawMaterial;

    @Column(nullable = false, updatable = false)
    private long quantityMilli;

    /** What one is expected to cost. What it actually cost is recorded when the goods arrive. */
    @Column(nullable = false, updatable = false)
    private long unitCostPaise;

    /** How much actually turned up. Nothing until the order is received. */
    @Column(nullable = false)
    private long receivedMilli;

    protected PurchaseOrderLine() {
    }

    public PurchaseOrderLine(Product product, RawMaterial rawMaterial, long quantityMilli, long unitCostPaise) {
        if ((product == null) == (rawMaterial == null)) {
            throw new IllegalArgumentException("A line is for one item: a product or a raw material, not both");
        }
        this.product = product;
        this.rawMaterial = rawMaterial;
        this.quantityMilli = quantityMilli;
        this.unitCostPaise = Math.max(0, unitCostPaise);
    }

    void placedOn(PurchaseOrder order) {
        this.order = order;
    }

    /** What actually arrived, which may be less than what was ordered. */
    void received(long milli) {
        this.receivedMilli = Math.max(0, milli);
    }

    public boolean isFromTheStore() {
        return rawMaterial != null;
    }

    public PurchaseOrder getOrder() {
        return order;
    }

    public Product getProduct() {
        return product;
    }

    public RawMaterial getRawMaterial() {
        return rawMaterial;
    }

    public long getQuantityMilli() {
        return quantityMilli;
    }

    public long getUnitCostPaise() {
        return unitCostPaise;
    }

    public long getReceivedMilli() {
        return receivedMilli;
    }

    public String getItemCode() {
        return product != null ? product.getSku() : rawMaterial.getCode();
    }

    public String getItemName() {
        return product != null ? product.getName() : rawMaterial.getName();
    }

    public UnitOfMeasure getUnit() {
        return product != null ? product.getUnit() : rawMaterial.getUnit();
    }

    /** Quantity times unit cost, in paise: milli-units times paise is a thousand times the money. */
    public long getValuePaise() {
        return Math.round(quantityMilli * unitCostPaise / 1000.0);
    }

    /** What actually arrived came to, which is what the vendor can ask to be paid for. */
    public long getReceivedValuePaise() {
        return Math.round(receivedMilli * unitCostPaise / 1000.0);
    }
}
