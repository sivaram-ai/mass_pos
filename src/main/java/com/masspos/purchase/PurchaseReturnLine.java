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
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

/**
 * One thing going back to the vendor: how much of it, and what the shop paid for it.
 *
 * <p>Shaped exactly like an order's line, down to taking a name instead of an item, because the
 * same things can go back as came: something off the shelf, something out of the store, or a
 * one-off nothing counts.
 */
@Entity
@Audited
@Table(name = "purchase_return_line")
public class PurchaseReturnLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_return_id", nullable = false, updatable = false)
    private PurchaseReturn sentBack;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_material_id", updatable = false)
    private RawMaterial rawMaterial;

    @Size(max = 200)
    @Column(name = "item_name", length = 200, nullable = false, updatable = false,
            columnDefinition = "varchar(200) default ''")
    private String itemName = "";

    @Size(max = 40)
    @Column(name = "item_code", length = 40, nullable = false, updatable = false,
            columnDefinition = "varchar(40) default ''")
    private String itemCode = "";

    @Column(nullable = false, updatable = false)
    private long quantityMilli;

    /** What the shop paid for one, which is what it stops owing by sending one back. */
    @Column(nullable = false, updatable = false)
    private long unitCostPaise;

    protected PurchaseReturnLine() {
    }

    public PurchaseReturnLine(Product product, RawMaterial rawMaterial, long quantityMilli, long unitCostPaise) {
        if (product != null && rawMaterial != null) {
            throw new IllegalArgumentException("A line is for one item: a product or a raw material, not both");
        }
        if (product == null && rawMaterial == null) {
            throw new IllegalArgumentException("Say what is going back");
        }
        this.product = product;
        this.rawMaterial = rawMaterial;
        this.quantityMilli = quantityMilli;
        this.unitCostPaise = Math.max(0, unitCostPaise);
    }

    public PurchaseReturnLine(String itemName, String itemCode, long quantityMilli, long unitCostPaise) {
        if (itemName == null || itemName.isBlank()) {
            throw new IllegalArgumentException("Say what is going back");
        }
        this.itemName = itemName.trim();
        this.itemCode = itemCode == null ? "" : itemCode.trim();
        this.quantityMilli = quantityMilli;
        this.unitCostPaise = Math.max(0, unitCostPaise);
    }

    void sentBackOn(PurchaseReturn sentBack) {
        this.sentBack = sentBack;
    }

    public boolean isFromTheStore() {
        return rawMaterial != null;
    }

    public boolean isOneOff() {
        return product == null && rawMaterial == null;
    }

    public PurchaseReturn getSentBack() {
        return sentBack;
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

    public String getItemCode() {
        if (isOneOff()) {
            return itemCode == null ? "" : itemCode;
        }
        return product != null ? product.getSku() : rawMaterial.getCode();
    }

    public String getItemName() {
        if (isOneOff()) {
            return itemName == null ? "" : itemName;
        }
        return product != null ? product.getName() : rawMaterial.getName();
    }

    public UnitOfMeasure getUnit() {
        if (isOneOff()) {
            return UnitOfMeasure.NOS;
        }
        return product != null ? product.getUnit() : rawMaterial.getUnit();
    }

    /** Quantity times unit cost, in paise: milli-units times paise is a thousand times the money. */
    public long getValuePaise() {
        return Math.round(quantityMilli * unitCostPaise / 1000.0);
    }
}
