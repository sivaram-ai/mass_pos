package com.masspos.billing;

/** How a bill was paid. A bill can be split across several of these. */
public enum PaymentMode {
    CASH,
    UPI,
    CARD,
    /** Meal cards, gift vouchers, wallet credit. */
    VOUCHER,
    /** Credit sale: money owed by a known customer. */
    ON_ACCOUNT
}
