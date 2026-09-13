package com.masspos.user;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * A person who operates the till. Never deleted, only deactivated: invoices and audit revisions
 * keep pointing at the user who made them.
 */
@Entity
@Audited
// "user" is a reserved word in PostgreSQL, the sync target.
@Table(name = "app_user", uniqueConstraints = @UniqueConstraint(name = "uk_app_user_username", columnNames = "username"))
public class User extends BaseEntity {

    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, length = 50)
    private String username;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String displayName;

    /** BCrypt hash of the till PIN. Not audited: the edit log should not carry credential material. */
    @NotAudited
    @NotBlank
    @Column(nullable = false, length = 100)
    private String pinHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    private boolean active = true;

    /** Set on a new or reset account: the first sign-in must choose a PIN before billing. */
    private boolean mustChangePin;

    protected User() {
    }

    public User(String username, String displayName, String pinHash, UserRole role) {
        this.username = username;
        this.displayName = displayName;
        this.pinHash = pinHash;
        this.role = role;
    }

    public void changeRole(UserRole role) {
        this.role = role;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public void changePinHash(String pinHash) {
        this.pinHash = pinHash;
    }

    public void requirePinChange() {
        this.mustChangePin = true;
    }

    /** The user chose their own PIN, so the forced change is satisfied. */
    public void pinChanged() {
        this.mustChangePin = false;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPinHash() {
        return pinHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isMustChangePin() {
        return mustChangePin;
    }
}
