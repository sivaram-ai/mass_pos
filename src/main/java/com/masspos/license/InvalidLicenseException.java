package com.masspos.license;

/** A licence key that cannot be trusted. The message reads after "Licence key" or "The installed licence key". */
public class InvalidLicenseException extends RuntimeException {

    public InvalidLicenseException(String message) {
        super(message);
    }
}
