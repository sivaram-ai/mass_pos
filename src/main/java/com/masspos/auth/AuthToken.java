package com.masspos.auth;

import com.masspos.common.persistence.BaseEntity;
import com.masspos.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.Instant;

/**
 * A sign-in that stays valid until someone logs out: a shop floor does not want a cashier thrown
 * out mid-queue by an idle timeout. Only the SHA-256 of the token is stored, so reading the
 * database does not let anyone impersonate a user, and the hash is kept out of the audit log.
 */
@Entity
@Audited
@Table(name = "auth_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_auth_token_hash", columnNames = "token_hash"))
public class AuthToken extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @NotAudited
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    /** Which machine this person signed in on. */
    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    private Instant revokedAt;

    @Column(length = 24)
    private String revokeReason;

    protected AuthToken() {
    }

    public AuthToken(User user, String tokenHash, String terminalCode) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.terminalCode = terminalCode;
    }

    public void revoke(String reason, Instant at) {
        if (revokedAt == null) {
            this.revokedAt = at;
            this.revokeReason = reason;
        }
    }

    public User getUser() {
        return user;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }
}
