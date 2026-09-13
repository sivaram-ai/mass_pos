package com.masspos.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import org.hibernate.Hibernate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseEntity {

    /** Stored as 36-char text (hibernate.type.preferred_uuid_jdbc_type=CHAR); v7 text sorts by time. */
    @Id
    @GeneratedUuidV7
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Optimistic lock. Also how Spring Data tells new from existing entities when ids are
     * pre-assigned: a null version means "not yet persisted".
     */
    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        // Rows replicated from another terminal keep their origin timestamps.
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Keeps the origin id of a row replicated from another terminal or the cloud. Only before persist. */
    public void assignId(UUID id) {
        if (this.id != null) {
            throw new IllegalStateException("Id is already set: " + this.id);
        }
        this.id = Objects.requireNonNull(id, "id");
    }

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BaseEntity other) || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        // getId(), not the field: the other side may be an uninitialised lazy proxy.
        return id != null && id.equals(other.getId());
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
