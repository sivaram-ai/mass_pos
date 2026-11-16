package com.masspos.rawmaterial;

import com.masspos.catalog.UnitOfMeasure;
import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

/**
 * Something the shop buys and uses but never sells: butter, onions, oil, flour, carrier bags.
 *
 * <p>A table of its own rather than a flag on {@link com.masspos.catalog.Product}, and the reason
 * is the billing grid. A cashier typing "on" must not be offered Onions among the things they can
 * ring up, and the surest way to guarantee that is for raw materials never to be in the catalogue
 * at all - not filtered out of it by a query somebody might later forget. The two have different
 * questions asked of them as well: a product has a selling price, an MRP, a GST rate and an HSN
 * code, and none of those mean anything for a sack of potatoes.
 *
 * <p>No stock column, for the same reason products have none: what is in the store is the sum of
 * {@link RawMaterialLedgerEvent} rows.
 */
@Entity
@Audited
@Table(name = "raw_material",
        uniqueConstraints = @UniqueConstraint(name = "uk_raw_material_code", columnNames = "code"))
public class RawMaterial extends BaseEntity {

    /** What a deleted material's code becomes, so the code is free again. See Product. */
    public static final String TOMBSTONE_PREFIX = "~";

    @NotBlank
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String code;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private UnitOfMeasure unit = UnitOfMeasure.NOS;

    /**
     * What the shop wants to be told about before it runs out - 2 kg of butter, say. Zero means
     * nobody is watching this one.
     */
    @Column(nullable = false)
    private long reorderLevelMilli;

    /** A material the shop has stopped using. It stays on the list, marked, with its history. */
    @Column(nullable = false)
    private boolean active = true;

    /** Deleted for real only in name: the movements that reference it are append-only. */
    @Column(nullable = false)
    private boolean deleted;

    protected RawMaterial() {
    }

    public RawMaterial(String code, String name, UnitOfMeasure unit, long reorderLevelMilli) {
        describe(code, name, unit, reorderLevelMilli);
    }

    public void describe(String code, String name, UnitOfMeasure unit, long reorderLevelMilli) {
        this.code = code == null ? "" : code.trim();
        this.name = name == null ? "" : name.trim();
        this.unit = unit == null ? UnitOfMeasure.NOS : unit;
        this.reorderLevelMilli = Math.max(0, reorderLevelMilli);
    }

    /**
     * Stops it being offered, without losing what the store did with it. The same distinction
     * products make: stopped is not deleted.
     */
    public void stop() {
        this.active = false;
    }

    public void start() {
        this.active = true;
    }

    /**
     * Gone from every screen, with its code freed for something else. The movements referencing it
     * cannot be removed - they are append-only and they are what the counts were made of - so the
     * row stays and its code is tombstoned, exactly as a deleted product's is.
     */
    public void delete() {
        this.deleted = true;
        this.active = false;
        this.code = TOMBSTONE_PREFIX + getId();
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public UnitOfMeasure getUnit() {
        return unit;
    }

    public long getReorderLevelMilli() {
        return reorderLevelMilli;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
