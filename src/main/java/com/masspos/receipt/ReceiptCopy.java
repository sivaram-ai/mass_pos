package com.masspos.receipt;

public enum ReceiptCopy {
    ORIGINAL("ORIGINAL FOR RECIPIENT"),
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
