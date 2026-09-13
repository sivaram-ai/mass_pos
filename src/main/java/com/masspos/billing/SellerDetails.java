package com.masspos.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;

/**
 * The seller as it was when an invoice was issued, copied from the shop settings. Invoices carry
 * this snapshot rather than reading the current configuration, so a reprint of an old invoice
 * still shows the name, address and registrations that were valid on that day.
 *
 * <p>A seller without a GSTIN is stored with an empty GSTIN and state code (never null, so the
 * columns keep their NOT NULL constraint): its bills charge no tax and are not tax invoices.
 */
@Embeddable
public class SellerDetails {

    @NotBlank
    @Column(name = "seller_legal_name", nullable = false, updatable = false, length = 200)
    private String legalName;

    @NotBlank
    @Column(name = "seller_trade_name", nullable = false, updatable = false, length = 200)
    private String tradeName;

    /** Empty for a shop that is not registered for GST. */
    @ValidGstin
    @Column(name = "seller_gstin", nullable = false, updatable = false, length = 15)
    private String gstin;

    /** Empty for a shop that is not registered for GST. */
    @Column(name = "seller_state_code", nullable = false, updatable = false, length = 2)
    private String stateCode;

    /** Address lines joined with '\n'. */
    @NotBlank
    @Column(name = "seller_address", nullable = false, updatable = false, length = 500)
    private String address;

    @Column(name = "seller_cin", updatable = false, length = 21)
    private String cin;

    @Column(name = "seller_fssai_license", updatable = false, length = 14)
    private String fssaiLicense;

    protected SellerDetails() {
    }

    /**
     * @param tradeName    falls back to the legal name when blank
     * @param gstin        blank for a shop that is not registered for GST
     * @param cin          optional
     * @param fssaiLicense optional
     */
    public SellerDetails(String legalName, String tradeName, String gstin, List<String> addressLines, String cin,
                         String fssaiLicense) {
        this.legalName = Objects.requireNonNull(legalName, "legalName");
        this.tradeName = tradeName == null || tradeName.isBlank() ? legalName : tradeName;
        this.gstin = gstin == null ? "" : gstin.trim();
        this.stateCode = this.gstin.isEmpty() ? "" : Gstin.stateCode(this.gstin);
        this.address = String.join("\n", addressLines);
        this.cin = blankToNull(cin);
        this.fssaiLicense = blankToNull(fssaiLicense);
    }

    /** Snapshot of the shop's settings. Refuses while the shop's name or address is missing. */
    public static SellerDetails from(com.masspos.settings.ShopSettings shop) {
        List<String> missing = shop.missingForInvoicing();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Invoices cannot be issued until the shop's "
                    + String.join(", ", missing) + " are set on the settings screen");
        }
        return new SellerDetails(shop.getLegalName(), shop.getTradeName(), shop.getGstin(), shop.addressLines(),
                shop.getCin(), shop.getFssaiLicense());
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

    public String getStateCode() {
        return stateCode;
    }

    /** False when the shop had no GSTIN at issue: no tax was charged on that bill. */
    public boolean isGstRegistered() {
        return !gstin.isEmpty();
    }

    public List<String> getAddressLines() {
        return List.of(address.split("\n"));
    }

    public String getCin() {
        return cin;
    }

    public String getFssaiLicense() {
        return fssaiLicense;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
