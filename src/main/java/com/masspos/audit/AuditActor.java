package com.masspos.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is making a change.
 *
 * @param userId   null for {@link #SYSTEM}
 * @param username as it is at the time of the change
 */
public record AuditActor(UUID userId, String username) {

    /** Background work with no logged-in user: startup jobs, sync. */
    public static final AuditActor SYSTEM = new AuditActor(null, "SYSTEM");

    public AuditActor {
        Objects.requireNonNull(username, "username");
    }
}
