package com.masspos.terminal;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

import java.time.Instant;

/**
 * One billing machine in the shop: "T2 / Counter 2 / 1st floor". Each till registers itself from
 * its own {@code pos.properties} at startup, so once counters replicate to the master, the master
 * lists every machine and the floor it stands on.
 */
@Entity
@Audited
@Table(name = "terminal", uniqueConstraints = @UniqueConstraint(name = "uk_terminal_code", columnNames = "code"))
public class Terminal extends BaseEntity {

    /** Matches {@code pos.terminal.code}; prefixes this machine's invoice numbers. */
    @NotBlank
    @Pattern(regexp = "[A-Z][A-Z0-9]{0,2}")
    @Column(nullable = false, updatable = false, length = 3)
    private String code;

    @NotBlank
    @Size(max = 60)
    @Column(nullable = false, length = 60)
    private String name;

    /** Floor, hall or area: "Ground floor", "1st floor", "Bar", "Takeaway". */
    @Size(max = 60)
    @Column(length = 60)
    private String section;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TerminalType type;

    private boolean active = true;

    /** Last time this machine started or reported in. The master uses it to spot a dead counter. */
    private Instant lastSeenAt;

    protected Terminal() {
    }

    public Terminal(String code, String name, String section, TerminalType type) {
        this.code = code;
        this.name = name;
        this.section = section;
        this.type = type;
    }

    /** Applies this machine's own settings; called on every start by {@code TerminalRegistry}. */
    public void describe(String name, String section, TerminalType type, Instant seenAt) {
        this.name = name;
        this.section = section;
        this.type = type;
        this.lastSeenAt = seenAt;
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSection() {
        return section;
    }

    public TerminalType getType() {
        return type;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
