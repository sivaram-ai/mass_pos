package com.masspos.catalog;

/**
 * GST Unique Quantity Codes (UQC), as reported in the GSTR-1 HSN summary. Quantities are stored in
 * milli-units everywhere, so 1.250 kg is 1250 and 2 pieces is 2000.
 */
public enum UnitOfMeasure {
    NOS(true),
    PCS(true),
    BOX(true),
    PAC(true),
    DOZ(true),
    KGS(false),
    GMS(false),
    LTR(false),
    MLT(false),
    MTR(false);

    private final boolean wholeUnitsOnly;

    UnitOfMeasure(boolean wholeUnitsOnly) {
        this.wholeUnitsOnly = wholeUnitsOnly;
    }

    /** True when a quantity must be a multiple of 1000 milli-units (no half pieces). */
    public boolean isWholeUnitsOnly() {
        return wholeUnitsOnly;
    }
}
