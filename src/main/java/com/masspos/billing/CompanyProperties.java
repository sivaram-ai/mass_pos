package com.masspos.billing;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The seller printed on every invoice, configured per till in {@code <data-dir>/pos.properties}.
 *
 * <p>Everything is optional at startup, so a fresh till can start and be configured. Anything that
 * is set must be valid, though: a mistyped GSTIN on a tax invoice costs the buyer their input tax
 * credit, so a typo stops the app instead of reaching paper. Issuing an invoice additionally
 * requires {@link #missingForInvoicing()} to be empty (enforced by {@link SellerDetails#from}).
 *
 * @param legalName    name exactly as on the GST registration certificate
 * @param tradeName    shop name printed large on receipts; defaults to the legal name
 * @param gstin        15-character GSTIN; its first two digits are the seller's state
 * @param address      address of this place of business, one entry per printed line
 * @param cin          Corporate Identity Number; a company must print it on its bills (Companies Act, s.12)
 * @param fssaiLicense 14-digit FSSAI licence or registration number; required on bills of food businesses
 * @param upiVpa       when set, original receipts carry a UPI QR code for the bill amount
 */
@Validated
@ConfigurationProperties(prefix = "pos.company")
public record CompanyProperties(
        @Size(max = 200) String legalName,
        @Size(max = 200) String tradeName,
        @ValidGstin String gstin,
        List<@Size(max = 100) String> address,
        @Size(max = 40) String phone,
        @Email @Size(max = 100) String email,
        @Size(max = 100) String website,
        @Pattern(regexp = "|[LU]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6}", message = "is not a valid 21-character CIN") String cin,
        @Pattern(regexp = "|\\d{14}", message = "must be the 14-digit FSSAI number") String fssaiLicense,
        @Pattern(regexp = "|[\\w.\\-]{2,256}@[A-Za-z]{2,64}", message = "is not a valid UPI ID") String upiVpa) {

    public CompanyProperties {
        legalName = trim(legalName);
        tradeName = trim(tradeName);
        gstin = trim(gstin).toUpperCase(Locale.ROOT);
        address = address == null ? List.of()
                : address.stream().map(CompanyProperties::trim).filter(line -> !line.isEmpty()).toList();
        phone = trim(phone);
        email = trim(email);
        website = trim(website);
        cin = trim(cin).toUpperCase(Locale.ROOT);
        fssaiLicense = trim(fssaiLicense);
        upiVpa = trim(upiVpa);
    }

    /** Properties that must still be set before this till may bill. Without a GSTIN it bills without tax. */
    public List<String> missingForInvoicing() {
        List<String> missing = new ArrayList<>();
        if (legalName.isEmpty()) {
            missing.add("pos.company.legal-name");
        }
        if (address.isEmpty()) {
            missing.add("pos.company.address[0]");
        }
        return missing;
    }

    /** Trade name, else legal name; empty if neither is set. */
    public String displayName() {
        return tradeName.isEmpty() ? legalName : tradeName;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
