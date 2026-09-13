package com.masspos.inventory;

/** Kind of stock movement, with the sign its quantity delta must carry. */
public enum LedgerEventType {
    OPENING_STOCK(1),
    PURCHASE_RECEIPT(1),
    PURCHASE_RETURN(-1),
    SALE(-1),
    SALE_RETURN(1),
    /** Reverses the SALE events of a cancelled invoice. */
    SALE_CANCELLED(1),
    DAMAGE(-1),
    TRANSFER_IN(1),
    TRANSFER_OUT(-1),
    /** Stock-take correction, either direction. */
    ADJUSTMENT(0);

    private final int sign;

    LedgerEventType(int sign) {
        this.sign = sign;
    }

    void checkDelta(long quantityDeltaMilli) {
        if (quantityDeltaMilli == 0) {
            throw new IllegalArgumentException("A ledger event must move stock");
        }
        if (sign != 0 && Long.signum(quantityDeltaMilli) != sign) {
            throw new IllegalArgumentException(
                    "%s requires a %s delta, got %d".formatted(this, sign > 0 ? "positive" : "negative", quantityDeltaMilli));
        }
    }
}
