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
 * One thing on an order: how much of it, and what it is expected to cost.
 *
 * <p>It points at a product <em>or</em> a raw material - two nullable keys rather than a loose id
 * with a kind beside it, so the database still refuses a line pointing at something that is not
 * there. The shelf and the store are different tables, and an order that mixes bottled water and
 * onions is an ordinary order.
 *
 * <p><b>Or at neither, and then it carries its own name.</b> A shop orders things it does not keep
 * a count of: a gas regulator, a day of a plumber's time, a box of something it will never buy
 * again. Refusing those would mean a manager inventing a catalogue item to get an order written,
 * which is how catalogues fill up with rubbish - so a line may be a name and a price, and nothing
 * else. Nothing counts it when it arrives, because there is nothing to count; the order still
 * records what was bought and the vendor is still owed for it.
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

    /**
     * What it is called, on a line that is neither - written down as it was typed, because there
     * is nothing else to read it off later. Empty on a line that points at an item.
     */
    @Size(max = 200)
    @Column(name = "item_name", length = 200, nullable = false, updatable = false,
            columnDefinition = "varchar(200) default ''")
    private String itemName = "";

    /** Whatever the vendor calls it - a part number off his list. Optional, and often empty. */
    @Size(max = 40)
    @Column(name = "item_code", length = 40, nullable = false, updatable = false,
            columnDefinition = "varchar(40) default ''")
    private String itemCode = "";

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

    /** A line for something on one of the shop's two lists. */
    public PurchaseOrderLine(Product product, RawMaterial rawMaterial, long quantityMilli, long unitCostPaise) {
        if (product != null && rawMaterial != null) {
            throw new IllegalArgumentException("A line is for one item: a product or a raw material, not both");
        }
        if (product == null && rawMaterial == null) {
            throw new IllegalArgumentException("Say what the line is for");
        }
        this.product = product;
        this.rawMaterial = rawMaterial;
        this.quantityMilli = quantityMilli;
        this.unitCostPaise = Math.max(0, unitCostPaise);
    }

    /** A line for something the shop does not keep: a name, and what it is expected to cost. */
    public PurchaseOrderLine(String itemName, String itemCode, long quantityMilli, long unitCostPaise) {
        if (itemName == null || itemName.isBlank()) {
            throw new IllegalArgumentException("Say what the line is for");
        }
        this.itemName = itemName.trim();
        this.itemCode = itemCode == null ? "" : itemCode.trim();
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

    /** True for a line that is a name and nothing else: nothing on either list counts it. */
    public boolean isOneOff() {
        return product == null && rawMaterial == null;
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

    /**
     * How it is counted. A one-off is counted in pieces, because nothing counts it at all: there
     * is no item to carry a unit, and a column holding one would be a second place for the same
     * fact to be wrong.
     */
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

    /** What actually arrived came to, which is what the vendor can ask to be paid for. */
    public long getReceivedValuePaise() {
        return Math.round(receivedMilli * unitCostPaise / 1000.0);
    }
}
