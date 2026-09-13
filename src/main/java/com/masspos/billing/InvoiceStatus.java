package com.masspos.billing;

/** No DRAFT: an invoice row exists only once a sale is final, so abandoned carts never consume a number. */
public enum InvoiceStatus {
    ISSUED,
    CANCELLED
}
