package com.masspos.theme;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

/**
 * The shop's one theme: which colour scheme every screen on every counter wears, and the custom
 * schemes saved for reuse. One row, in the database, so it is the same wherever somebody signs in.
 *
 * <p>{@code revision} is what counters poll: a screen asks only "has it changed?", a number, and
 * fetches the theme when the answer is yes.
 *
 * <p>Audited like the rest of the shop's settings: who restyled the till, and when, is a fair thing
 * to be able to find out. A new table, so a till already in the field takes it on upgrade.
 */
@Entity
@Audited
@Table(name = "theme_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_theme_settings_singleton", columnNames = "singleton"))
public class ThemeSettings extends BaseEntity {

    @Column(nullable = false)
    private int singleton = 1;

    /** Bumped on every change; starts at 1. */
    @Column(nullable = false)
    private long revision = 1;

    /** The theme in force, as JSON. Empty = the default. */
    @Column(nullable = false, length = 20000)
    private String activeJson = "";

    /** The saved custom themes, as a JSON array. */
    @Column(nullable = false, length = 200000)
    private String libraryJson = "[]";

    public long getRevision() {
        return revision;
    }

    public String getActiveJson() {
        return activeJson;
    }

    public String getLibraryJson() {
        return libraryJson;
    }

    public void change(String activeJson, String libraryJson) {
        this.activeJson = activeJson;
        this.libraryJson = libraryJson;
        this.revision++;
    }
}
