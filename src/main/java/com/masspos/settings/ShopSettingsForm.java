package com.masspos.settings;

import com.masspos.billing.ValidGstin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The settings screen, as posted back. Anything filled in must be valid; anything still blank only
 * stops invoicing once it is actually needed, so a shop can be set up in whatever order suits it.
 *
 * @param address       one entry per printed line
 * @param receiptFooter lines printed at the bottom of every receipt
 */
public record ShopSettingsForm(@Size(max = 200) String legalName,
                               @Size(max = 200) String tradeName,
                               @ValidGstin String gstin,
                               List<@Size(max = 100) String> address,
                               @Size(max = 40) String phone,
                               @Email @Size(max = 100) String email,
                               @Size(max = 100) String website,
                               @Pattern(regexp = "|[LU]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6}",
                                       message = "is not a valid 21-character CIN") String cin,
                               @Pattern(regexp = "|\\d{14}",
                                       message = "must be the 14-digit FSSAI number") String fssaiLicense,
                               @Pattern(regexp = "|[\\w.\\-]{2,256}@[A-Za-z]{2,64}",
                                       message = "is not a valid UPI ID") String upiVpa,
                               List<@Size(max = 200) String> receiptFooter,
                               @Size(max = 100) String serviceProviderName,
                               @Size(max = 40) String servicePhone,
                               @Email @Size(max = 100) String serviceEmail,
                               boolean roundInvoiceTotal,
                               boolean blockNegativeStock) {
}
