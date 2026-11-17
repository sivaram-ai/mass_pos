package com.masspos.vendor;

import com.masspos.billing.Gstin;
import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

/**
 * Somebody the shop buys from: the vegetable man, the gas agency, the packaging wholesaler.
 *
 * <p><b>Not a {@code Supplier}.</b> That word is already taken in this codebase and means the
 * opposite end of the shop - the steward who carried the order to the table, named on hotel bills
 * beside the table number, with {@code supplier_id} frozen on every invoice. Renaming it to free
 * the word would mean altering a populated, audited, printed column, which is the SQLite trap
 * this project has been bitten by before. So the people goods come <em>from</em> are vendors, and
 * the people who carry food <em>out</em> are suppliers, and the two never meet.
 *
 * <p>A vendor is a record the shop keeps, not a thing a movement points at for its meaning: a
 * delivery still writes the vendor's name onto itself as a snapshot. The link is here so a profile
 * can gather what was bought and what is owed; the name on a three-year-old delivery stays what it
 * was even if this record is renamed or removed.
 */
@Entity
@Audited
@Table(name = "vendor", uniqueConstraints = @UniqueConstraint(name = "uk_vendor_code", columnNames = "code"))
public class Vendor extends BaseEntity {

    /** What a removed vendor's code becomes, so the code is free again. As Product does it. */
    public static final String TOMBSTONE_PREFIX = "~";

    @NotBlank
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String code;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    @Size(max = 20)
    @Column(length = 20, nullable = false)
    private String phone = "";

    /**
     * Optional, and checked only when it is there: plenty of a small shop's vendors are not
     * registered, and the ones that are matter at the accountant's end of the year.
     */
    @Size(max = 15)
    @Column(length = 15, nullable = false)
    private String gstin = "";

    @Size(max = 200)
    @Column(length = 200, nullable = false)
    private String address = "";

    @Size(max = 200)
    @Column(length = 200, nullable = false)
    private String note = "";

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean deleted;

    protected Vendor() {
    }

    public Vendor(String code, String name, String phone, String gstin, String address, String note) {
        describe(code, name, phone, gstin, address, note);
    }

    public void describe(String code, String name, String phone, String gstin, String address, String note) {
        this.code = trim(code);
        this.name = trim(name);
        this.phone = trim(phone);
        this.gstin = trim(gstin).toUpperCase();
        this.address = trim(address);
        this.note = trim(note);
        if (!this.gstin.isEmpty() && !Gstin.isValid(this.gstin)) {
            throw new com.masspos.web.FieldRejectedException("gstin", "is not a valid GSTIN");
        }
    }

    /** Stopped: not offered when buying, still on the list with everything it is owed. */
    public void stop() {
        this.active = false;
    }

    public void start() {
        this.active = true;
    }

    /**
     * Gone from the list, with its code free again. The deliveries and the payments stay: both are
     * records of money and goods that really moved, and both carry the name as it was.
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

    public String getPhone() {
        return phone;
    }

    public String getGstin() {
        return gstin;
    }

    public String getAddress() {
        return address;
    }

    public String getNote() {
        return note;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isDeleted() {
        return deleted;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
