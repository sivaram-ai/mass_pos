package com.masspos.license;

/** Billing refused because this till's licence does not allow it. The message says why and what to do. */
public class LicenseLockedException extends RuntimeException {

    public LicenseLockedException(String message) {
        super(message);
    }
}
