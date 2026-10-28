package com.masspos.client;

/**
 * This machine may not bill against this server: no counter number, one that is already the
 * server's, or no place left. Always carries a sentence the person standing at the counter can
 * act on, because it is the first thing they see and there is nobody else to ask.
 */
public class ClientRefusedException extends RuntimeException {

    public ClientRefusedException(String message) {
        super(message);
    }
}
