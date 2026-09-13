package com.masspos.audit;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread-bound acting user for audit revisions. The auth filter binds it per request; anything not
 * bound (sync, scheduled jobs) is recorded as {@link AuditActor#SYSTEM}.
 */
public final class AuditContext {

    private static final ThreadLocal<AuditActor> CURRENT = new ThreadLocal<>();

    private AuditContext() {
    }

    public static AuditActor currentActor() {
        AuditActor actor = CURRENT.get();
        return actor != null ? actor : AuditActor.SYSTEM;
    }

    public static <T> T callAs(AuditActor actor, Supplier<T> work) {
        AuditActor previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(actor, "actor"));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(AuditActor actor, Runnable work) {
        callAs(actor, () -> {
            work.run();
            return null;
        });
    }

    /**
     * For servlet filters, which cannot wrap the chain in a lambda. Always pair with {@link #clear()}
     * in a finally block: Tomcat reuses request threads, so a leftover actor would misattribute the
     * next request.
     */
    public static void bind(AuditActor actor) {
        CURRENT.set(Objects.requireNonNull(actor, "actor"));
    }

    public static void clear() {
        CURRENT.remove();
    }
}
