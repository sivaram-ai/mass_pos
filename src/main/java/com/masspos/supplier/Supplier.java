package com.masspos.supplier;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

/**
 * Whoever supplied the food on a bill: an ID and a name, and nothing else. In a hotel that is the
 * steward who carried the order to the table, named on the bill beside the table number so a
 * query at the counter can be traced back to the floor.
 *
 * <p>They are not people who use the till, which is why they are not in the user table. Putting
 * them there would mean a PIN column they never use, a sign-in they must be refused, and a fifth
 * value in the role enum - and SQLite writes that enum as a CHECK constraint it cannot afterwards
 * alter, so every till already in a shop would refuse the new value. A table of their own costs
 * one new table on upgrade, which SQLite does take.
 */
@Entity
@Audited
@Table(name = "supplier", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_code", columnNames = "code"))
public class Supplier extends BaseEntity {

    /** What the cashier types at the start of a bill, and what is printed on it. */
    @NotBlank
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String code;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    protected Supplier() {
    }

    public Supplier(String code, String name) {
        rename(code, name);
    }

    public void rename(String code, String name) {
        this.code = code == null ? "" : code.trim();
        this.name = name == null ? "" : name.trim();
    }

    public void activate() {
        this.active = true;
    }

    /** Kept, never deleted: bills they served carry this code and must still make sense. */
    public void deactivate() {
        this.active = false;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}