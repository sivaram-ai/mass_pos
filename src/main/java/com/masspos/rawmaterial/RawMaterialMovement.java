package com.masspos.rawmaterial;

/**
 * Kind of movement in a raw material's store, with the sign its quantity delta must carry.
 *
 * <p>Its own vocabulary rather than the ledger's, and that is not duplication for its own sake.
 * Nothing here is ever sold, so SALE, SALE_RETURN and SALE_CANCELLED would be three values that
 * can never occur; and a kitchen needs one the shelf does not - what was <em>used</em>, which is
 * where almost all of an onion's stock goes.
 *
 * <p>There is a harder reason too. Hibernate writes an {@code @Enumerated(STRING)} column as a
 * CHECK constraint listing every value, on the table and on its {@code _aud} twin, and SQLite
 * cannot alter one afterwards. Adding CONSUMED to the shared {@link
 * com.masspos.inventory.LedgerEventType} would have put a value in that list which every till
 * already in a shop would refuse. A new enum on a new table carries its own constraint, and new
 * tables are the one thing SQLite does take on upgrade.
 */
public enum RawMaterialMovement {
    /** What was in the store when the shop started counting it. */
    OPENING_STOCK(1),
    /** Bought in. The only movement anybody is asked a price or a vendor for. */
    PURCHASE(1),
    /** Taken into the kitchen and cooked with: where most of it goes. */
    CONSUMED(-1),
    /** Spoiled, spilt, thrown out. */
    WASTAGE(-1),
    /** Sent back to whoever it came from. */
    RETURNED_TO_VENDOR(-1),
    /** What a count of the store found, either direction. */
    ADJUSTMENT(0);

    private final int sign;

    RawMaterialMovement(int sign) {
        this.sign = sign;
    }

    void checkDelta(long quantityDeltaMilli) {
        if (quantityDeltaMilli == 0) {
            throw new IllegalArgumentException("A movement must move something");
        }
        if (sign != 0 && Long.signum(quantityDeltaMilli) != sign) {
            throw new IllegalArgumentException("%s requires a %s delta, got %d"
                    .formatted(this, sign > 0 ? "positive" : "negative", quantityDeltaMilli));
        }
    }
}
