package com.masspos.inventory;

/**
 * Why a count is being put right by hand.
 *
 * <p>Every manual movement of stock is one of these. Before them the screen had three buttons -
 * Received, Correct, Damage - and the first of those is gone: goods now arrive only against a
 * purchase order somebody agreed to. What is left is the shop saying what is actually on the
 * floor, and the honest answer to "why" is one of a short list of things that happen in shops.
 *
 * <p>Deliberately <b>not</b> a persisted enum. It chooses which ledger type is written and is
 * recorded in the movement's note; the ledger's own values are unchanged, because a new value in a
 * persisted enum is a CHECK constraint SQLite will not alter on a till that is already in a shop.
 *
 * @param label what it reads as on the screen and in the history
 * @param sign  -1 for a reason that can only take stock off, +1 for one that can only put it on,
 *              0 where the person doing it says which. A quantity typed the wrong way round is
 *              turned round rather than refused: nobody means "damaged minus five".
 */
public enum CorrectionReason {

    STOCK_TAKE("Stock take", 0, LedgerEventType.ADJUSTMENT),
    OPENING_STOCK("Opening stock", 1, LedgerEventType.OPENING_STOCK),
    DAMAGED("Damaged", -1, LedgerEventType.DAMAGE),
    EXPIRED("Expired", -1, LedgerEventType.DAMAGE),
    WASTED("Wasted or spilt", -1, LedgerEventType.DAMAGE),
    TAKEN("Taken without paying", -1, LedgerEventType.DAMAGE),
    SENT_BACK("Sent back to the vendor", -1, LedgerEventType.PURCHASE_RETURN),
    ENTERED_WRONGLY("Entered wrongly", 0, LedgerEventType.ADJUSTMENT);

    private final String label;
    private final int sign;
    private final LedgerEventType onTheShelf;

    CorrectionReason(String label, int sign, LedgerEventType onTheShelf) {
        this.label = label;
        this.sign = sign;
        this.onTheShelf = onTheShelf;
    }

    public String label() {
        return label;
    }

    /** Which way this reason moves a count: -1 off, +1 on, 0 either, as the person says. */
    public int sign() {
        return sign;
    }

    /** The movement written against the shelf. The store has its own, in its own package. */
    LedgerEventType onTheShelf() {
        return onTheShelf;
    }

    /** The typed amount, turned the only way this reason can go. */
    public long applyTo(long deltaMilli) {
        return switch (sign) {
            case -1 -> -Math.abs(deltaMilli);
            case 1 -> Math.abs(deltaMilli);
            default -> deltaMilli;
        };
    }

    /**
     * What the history will read against this movement: the reason, and then whatever was typed
     * beside it. Kept to the 200 characters the note column holds, cut at the notes rather than at
     * the reason, which is the part that has to survive.
     */
    public String wording(String notes) {
        String said = notes == null ? "" : notes.trim();
        if (said.isEmpty()) {
            return label;
        }
        String whole = label + " - " + said;
        return whole.length() <= 200 ? whole : whole.substring(0, 200);
    }
}
