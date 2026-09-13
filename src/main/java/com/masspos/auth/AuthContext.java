package com.masspos.auth;

/** Thread-bound signed-in user, set by {@link AuthFilter} for the duration of one request. */
public final class AuthContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    /** The signed-in user, or null on a public endpoint. */
    public static Principal current() {
        return CURRENT.get();
    }

    /** Never null on an authenticated endpoint; use it where a service must record who acted. */
    public static Principal require() {
        Principal principal = CURRENT.get();
        if (principal == null) {
            throw new AuthException("Not signed in");
        }
        return principal;
    }

    static void bind(Principal principal) {
        CURRENT.set(principal);
    }

    static void clear() {
        CURRENT.remove();
    }
}
