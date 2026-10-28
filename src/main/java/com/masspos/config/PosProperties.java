package com.masspos.config;

import com.masspos.terminal.TerminalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Seller identity lives in {@code pos.company} (CompanyProperties), printer settings in
 * {@code pos.printer}, receipt text in {@code pos.receipt}, sign-in defaults in {@code pos.auth}.
 *
 * @param dataDir  writable directory holding pos.db (+ WAL files), pos.properties, logs and printer output
 * @param terminal identity of this physical till
 * @param version  what this build is called, shown at the top of the Settings screen. Comes from the
 *                 pom through resource filtering; an installed till is told its own by
 *                 build-installer.ps1, which passes the installer's version as a JVM option, so a
 *                 shop reporting a problem names the build it actually has.
 */
@Validated
@ConfigurationProperties(prefix = "pos")
public record PosProperties(@NotNull Path dataDir, @Valid @NotNull Terminal terminal, String version) {

    /** Unfiltered (`@project.version@`) when run straight from an IDE, which is not a version. */
    public PosProperties {
        version = version == null || version.isBlank() || version.startsWith("@") ? "dev" : version.trim();
    }

    /**
     * @param code    invoice-number prefix and origin tag on audit revisions and ledger events. At
     *                most 3 characters so that invoice numbers stay within GST Rule 46's 16-character
     *                limit, and unique across the shop's tills.
     * @param name    what staff call this machine: "Counter 1", "Billing 2"
     * @param section floor or area it stands on: "Ground floor", "1st floor", "Bar"
     * @param type    MASTER holds the shop's consolidated database; COUNTER bills and syncs to it
     */
    public record Terminal(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9]{0,2}") String code,
                           @NotBlank @Size(max = 60) String name,
                           @Size(max = 60) String section,
                           @NotNull TerminalType type) {

        public Terminal {
            name = name == null || name.isBlank() ? code : name.trim();
            section = section == null ? "" : section.trim();
        }
    }
}
