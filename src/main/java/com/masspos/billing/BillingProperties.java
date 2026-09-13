package com.masspos.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param roundInvoiceTotal round the payable amount to whole rupees (section 170 of the CGST Act),
 *                          which is what shoppers expect on a cash bill
 * @param blockNegativeStock refuse to sell an item the ledger says is out of stock. Off by default:
 *                          a wrong stock count must never stop a paying customer at the counter.
 */
@Validated
@ConfigurationProperties(prefix = "pos.billing")
public record BillingProperties(boolean roundInvoiceTotal, boolean blockNegativeStock) {
}
