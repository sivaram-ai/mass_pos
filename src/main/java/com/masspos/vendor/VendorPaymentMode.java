package com.masspos.vendor;

/**
 * How a vendor was paid.
 *
 * <p>Its own list rather than the till's {@code PaymentMode}, because a shop pays its vendors in
 * ways no customer pays a bill - a cheque, a bank transfer - and is never paid by one in ways a
 * customer is: ON_ACCOUNT and VOUCHER mean nothing here. A new enum on a new table carries its own
 * CHECK constraint, which is also the only safe way to have a different list at all.
 */
public enum VendorPaymentMode {
    CASH,
    UPI,
    BANK_TRANSFER,
    CHEQUE,
    CARD
}
