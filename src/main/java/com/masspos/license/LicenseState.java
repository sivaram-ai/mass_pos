package com.masspos.license;

/** Where this till's licence stands today. */
public enum LicenseState {

    /** Paid up and outside the warning window. */
    ACTIVE,
    /** Paid up, but ending within the licence's warning days. */
    EXPIRING,
    /** Past its end date, inside the grace days: billing carries on with a warning. */
    GRACE,
    /** Past the grace days: billing is locked until a renewal is installed. */
    EXPIRED,
    /** No licence installed on this till. */
    NOT_ACTIVATED,
    /** The installed licence was issued for another computer. */
    WRONG_MACHINE,
    /** The installed licence cannot be trusted, or this computer's hardware ID cannot be read. */
    INVALID;

    public boolean allowsBilling() {
        return this == ACTIVE || this == EXPIRING || this == GRACE;
    }
}
