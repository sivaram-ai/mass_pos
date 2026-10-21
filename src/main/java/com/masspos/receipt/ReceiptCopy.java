package com.masspos.receipt;

public enum ReceiptCopy {
    /** The customer's own bill. Nothing is stamped on it: only a reprint needs marking. */
    ORIGINAL(""),
    /** Any reprint. Never opens the cash drawer. */
    DUPLICATE("DUPLICATE COPY");

    private final String label;

    ReceiptCopy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
