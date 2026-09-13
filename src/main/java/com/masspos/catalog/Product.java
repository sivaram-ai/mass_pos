package com.masspos.catalog;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

/**
 * A sellable item. There is deliberately no stock column: stock on hand is derived from the
 * inventory ledger. Money is in paise and rates in basis points, so no floating point is ever
 * involved (SQLite has no DECIMAL type and would store a BigDecimal as a REAL).
 */
@Entity
@Audited
@Table(name = "product", uniqueConstraints = {
        @UniqueConstraint(name = "uk_product_sku", columnNames = "sku"),
        @UniqueConstraint(name = "uk_product_barcode", columnNames = "barcode")})
public class Product extends BaseEntity {

    /** Empty, or 4, 6 or 8 digits. */
    public static final String HSN_PATTERN = "|\\d{4}|\\d{6}|\\d{8}";
    public static final String HSN_MESSAGE = "must be 4, 6 or 8 digits";

    @NotBlank
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String sku;

    /** EAN-13 / UPC / in-store code. Unique when present; SQLite allows many NULLs under UNIQUE. */
    @Size(max = 64)
    @Column(length = 64)
    private String barcode;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String name;

    /**
     * HSN (goods) or SAC (services) code. Optional, stored empty rather than null: a shop without a
     * GSTIN never needs one, and a small GST shop needs it only on some bills.
     */
    @NotNull
    @Pattern(regexp = HSN_PATTERN, message = HSN_MESSAGE)
    @Column(nullable = false, length = 8)
    private String hsnCode;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private UnitOfMeasure unit;

    /** Printed MRP per unit (Legal Metrology); 0 when the item carries none, e.g. loose produce. */
    @PositiveOrZero
    private long mrpPaise;

    @PositiveOrZero
    private long sellingPricePaise;

    /** True when the selling price already includes GST, as shelf prices usually do in B2C retail. */
    private boolean taxInclusive;

    /** Default GST rate in basis points (1800 = 18%). Not an enum: slabs change by notification. */
    @Min(0)
    @Max(10_000)
    private int gstRateBp;

    @Min(0)
    @Max(10_000)
    private int cessRateBp;

    private boolean active = true;

    protected Product() {
    }

    public Product(String sku, String name, String hsnCode, UnitOfMeasure unit, long sellingPricePaise,
                   long mrpPaise, boolean taxInclusive, int gstRateBp) {
        this.sku = sku;
        this.name = name;
        this.hsnCode = hsnCode == null ? "" : hsnCode.trim();
        this.unit = unit;
        this.sellingPricePaise = sellingPricePaise;
        this.mrpPaise = mrpPaise;
        this.taxInclusive = taxInclusive;
        this.gstRateBp = gstRateBp;
    }

    @AssertTrue(message = "the selling price must not be more than the MRP")
    boolean isPriceWithinMrp() {
        return mrpPaise == 0 || sellingPricePaise <= mrpPaise;
    }

    /** Name, HSN and unit as the catalogue shows them. Bills already issued keep their own copies. */
    public void describe(String name, String hsnCode, UnitOfMeasure unit) {
        this.name = name;
        this.hsnCode = hsnCode == null ? "" : hsnCode.trim();
        this.unit = unit;
    }

    public void reprice(long sellingPricePaise, long mrpPaise) {
        this.sellingPricePaise = sellingPricePaise;
        this.mrpPaise = mrpPaise;
    }

    public void changeTax(int gstRateBp, int cessRateBp, boolean taxInclusive) {
        this.gstRateBp = gstRateBp;
        this.cessRateBp = cessRateBp;
        this.taxInclusive = taxInclusive;
    }

    public void assignBarcode(String barcode) {
        this.barcode = barcode;
    }

    public void deactivate() {
        this.active = false;
    }

    public String getSku() {
        return sku;
    }

    public String getBarcode() {
        return barcode;
    }

    public String getName() {
        return name;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public UnitOfMeasure getUnit() {
        return unit;
    }

    public long getMrpPaise() {
        return mrpPaise;
    }

    public long getSellingPricePaise() {
        return sellingPricePaise;
    }

    public boolean isTaxInclusive() {
        return taxInclusive;
    }

    public int getGstRateBp() {
        return gstRateBp;
    }

    public int getCessRateBp() {
        return cessRateBp;
    }

    public boolean isActive() {
        return active;
    }
}
