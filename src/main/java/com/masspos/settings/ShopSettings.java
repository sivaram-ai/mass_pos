package com.masspos.settings;

import com.masspos.billing.Gstin;
import com.masspos.billing.ValidGstin;
import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything about the shop that staff can change from the settings screen: who the seller is on
 * every invoice, what the receipt says, and how bills are rounded. One row, held in the database
 * rather than in a file, so an owner can edit it on screen and the master can push it to every
 * counter.
 *
 * <p>Per-machine things stay in {@code pos.properties}: the terminal code, floor and printer.
 *
 * <p>Audited, because changing a GSTIN or a legal name is exactly the sort of edit Rule 11(g)
 * expects to see recorded. Invoices already issued keep their own copy and never change.
 */
@Entity
@Audited
@Table(name = "shop_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_shop_settings_singleton", columnNames = "singleton"))
public class ShopSettings extends BaseEntity {

    /** Always 1: a shop has exactly one set of settings. */
    @Column(nullable = false)
    private int singleton = 1;

    @Size(max = 200)
    @Column(length = 200)
    private String legalName = "";

    @Size(max = 200)
    @Column(length = 200)
    private String tradeName = "";

    @ValidGstin
    @Column(length = 15)
    private String gstin = "";

    /** Address lines joined with '\n'. */
    @Size(max = 500)
    @Column(length = 500)
    private String addressText = "";

    @Size(max = 40)
    @Column(length = 40)
    private String phone = "";

    @Size(max = 100)
    @Column(length = 100)
    private String email = "";

    @Size(max = 100)
    @Column(length = 100)
    private String website = "";

    @Pattern(regexp = "|[LU]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6}")
    @Column(length = 21)
    private String cin = "";

    @Pattern(regexp = "|\\d{14}")
    @Column(length = 14)
    private String fssaiLicense = "";

    @Pattern(regexp = "|[\\w.\\-]{2,256}@[A-Za-z]{2,64}")
    @Column(length = 100)
    private String upiVpa = "";

    /** Footer lines joined with '\n'. */
    @Size(max = 500)
    @Column(length = 500)
    private String receiptFooterText = "";

    /** Who services this till: the shop calls them when the printer or the machine fails. */
    @Size(max = 100)
    @Column(length = 100)
    private String serviceProviderName = "";

    @Size(max = 40)
    @Column(length = 40)
    private String servicePhone = "";

    @Size(max = 100)
    @Column(length = 100)
    private String serviceEmail = "";

    private boolean roundInvoiceTotal = true;

    private boolean blockNegativeStock;

    public ShopSettings() {
    }

    public void apply(ShopSettingsForm form) {
        this.legalName = trim(form.legalName());
        this.tradeName = trim(form.tradeName());
        this.gstin = trim(form.gstin()).toUpperCase(Locale.ROOT);
        this.addressText = joinLines(form.address());
        this.phone = trim(form.phone());
        this.email = trim(form.email());
        this.website = trim(form.website());
        this.cin = trim(form.cin()).toUpperCase(Locale.ROOT);
        this.fssaiLicense = trim(form.fssaiLicense());
        this.upiVpa = trim(form.upiVpa());
        this.receiptFooterText = joinLines(form.receiptFooter());
        this.serviceProviderName = trim(form.serviceProviderName());
        this.servicePhone = trim(form.servicePhone());
        this.serviceEmail = trim(form.serviceEmail());
        this.roundInvoiceTotal = form.roundInvoiceTotal();
        this.blockNegativeStock = form.blockNegativeStock();
    }

    /**
     * What must still be filled in before this till may issue any bill. The GSTIN is not on the list:
     * a shop without one bills without tax (see {@link #isGstRegistered()}).
     */
    public List<String> missingForInvoicing() {
        List<String> missing = new ArrayList<>();
        if (legalName.isEmpty()) {
            missing.add("legal name");
        }
        if (addressLines().isEmpty()) {
            missing.add("address");
        }
        return missing;
    }

    public boolean isReadyToInvoice() {
        return missingForInvoicing().isEmpty();
    }

    /**
     * A shop without a GSTIN is not registered for GST. It may still bill, but it cannot collect
     * tax, so its bills charge none and are not tax invoices.
     */
    public boolean isGstRegistered() {
        return !gstin.isEmpty();
    }

    /** Trade name, else legal name; empty when neither is set yet. */
    public String displayName() {
        return tradeName.isEmpty() ? legalName : tradeName;
    }

    public List<String> addressLines() {
        return splitLines(addressText);
    }

    public List<String> receiptFooter() {
        return splitLines(receiptFooterText);
    }

    /** Seller's state, taken from the GSTIN so the two can never disagree; empty without a GSTIN. */
    public String stateCode() {
        return gstin.length() >= 2 ? Gstin.stateCode(gstin) : "";
    }

    public String getLegalName() {
        return legalName;
    }

    public String getTradeName() {
        return tradeName;
    }

    public String getGstin() {
        return gstin;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getWebsite() {
        return website;
    }

    public String getCin() {
        return cin;
    }

    public String getFssaiLicense() {
        return fssaiLicense;
    }

    public String getUpiVpa() {
        return upiVpa;
    }

    public String getServiceProviderName() {
        return serviceProviderName;
    }

    public String getServicePhone() {
        return servicePhone;
    }

    public String getServiceEmail() {
        return serviceEmail;
    }

    public boolean isRoundInvoiceTotal() {
        return roundInvoiceTotal;
    }

    public boolean isBlockNegativeStock() {
        return blockNegativeStock;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String joinLines(List<String> lines) {
        return lines == null ? "" : String.join("\n", lines.stream()
                .filter(line -> line != null && !line.isBlank())
                .map(String::trim)
                .toList());
    }

    private static List<String> splitLines(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text.split("\n"));
    }
}
