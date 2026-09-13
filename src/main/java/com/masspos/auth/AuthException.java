package com.masspos.auth;

/** Wrong credentials or no usable token. Vague on purpose: it never says which part failed. */
public class AuthException extends RuntimeException {

    public AuthException(String message) {
        super(message);
    }
}
