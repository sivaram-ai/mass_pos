package com.masspos.auth;

import java.time.Instant;

/**
 * This account is already signed in somewhere else in the shop.
 *
 * <p>Not an error, and deliberately not refused outright: one person moving between two tills is
 * ordinary, and so is a cashier whose last machine was carried off without them signing out. What
 * is not ordinary is finding out afterwards - so the sign-in stops here, says where the account is
 * already in use, and lets the person decide whether to take it over.
 *
 * <p>It carries the till in the words a shop uses for it rather than anything of the machine's
 * own: a counter is one machine with one name, and that name is what somebody standing in the
 * shop can recognise and walk over to.
 */
public class SessionConflictException extends RuntimeException {

    private final String counterCode;
    private final String counterName;
    private final String where;
    private final Instant since;

    public SessionConflictException(String counterCode, String counterName, String where, Instant since) {
        super("This account is already signed in at " + counterName
                + (counterCode.isBlank() ? "" : " (" + counterCode + ")") + ".");
        this.counterCode = counterCode;
        this.counterName = counterName;
        this.where = where;
        this.since = since;
    }

    public String getCounterCode() {
        return counterCode;
    }

    public String getCounterName() {
        return counterName;
    }

    /** The floor or area that till stands on, when the shop has said; empty otherwise. */
    public String getWhere() {
        return where;
    }

    /** When that sign-in happened, so somebody can tell this morning's from five minutes ago. */
    public Instant getSince() {
        return since;
    }
}
