package com.masspos.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.util.UUID;

/**
 * One Envers revision: one transaction that touched audited entities. Every {@code *_aud} row
 * points here, so this is where the who / when / where of the MCA Rule 11(g) edit log is recorded.
 * Append-only at the database level (see {@link AuditTrailGuard}).
 */
@Entity
@Table(name = "audit_revision")
@RevisionEntity(AuditRevisionListener.class)
public class AuditRevision {

    /** Local, per-terminal sequence (Envers requires a numeric revision number). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    private Long id;

    /** Transaction time, Unix epoch milliseconds. */
    @RevisionTimestamp
    @Column(nullable = false)
    private long timestamp;

    /** Globally unique handle for the cloud copy, where {@code id} alone would collide across terminals. */
    @Column(nullable = false, updatable = false, unique = true)
    private UUID revisionUuid;

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    /** Null when the change was made by {@link AuditActor#SYSTEM}. */
    @Column(updatable = false)
    private UUID actorUserId;

    /** Username at the time of the change, so a later rename does not rewrite history. */
    @Column(nullable = false, updatable = false, length = 50)
    private String actorUsername;

    void stamp(UUID revisionUuid, String terminalCode, AuditActor actor) {
        this.revisionUuid = revisionUuid;
        this.terminalCode = terminalCode;
        this.actorUserId = actor.userId();
        this.actorUsername = actor.username();
    }

    public Long getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public UUID getRevisionUuid() {
        return revisionUuid;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorUsername() {
        return actorUsername;
    }
}
