package com.masspos.auth;

/** Signed in, but this role is not allowed to do it. */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
