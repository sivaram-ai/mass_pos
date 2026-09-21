package com.masspos.license;

import com.masspos.common.IndiaTime;
import com.masspos.config.PosProperties;
import com.masspos.license.LicensePolicy.Installed;
import com.masspos.license.LicensePolicy.MachineReading;
import com.masspos.web.FieldRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This till's licence: kept in {@code <data-dir>/license.lic}, checked before every bill.
 *
 * <p>Nothing here needs the network, and nothing here stops the app starting: a till without a valid
 * licence starts, signs in, and shows why it cannot bill. Only issuing documents is refused
 * ({@link RequiresLicense}).
 */
@Service
public class LicenseService {

    public static final String FILE_NAME = "license.lic";
    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    /** NTP corrections and a slow BIOS clock are not tampering. */
    private static final long CLOCK_TOLERANCE_MILLIS = Duration.ofHours(1).toMillis();

    private final Path file;
    private final VendorKey vendorKey;
    private final MachineIdentity machine;
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    private volatile Installed installed;
    /** The latest moment this till is known to have reached; -1 until read from the audit trail. */
    private final AtomicLong latestActivity = new AtomicLong(-1);

    public LicenseService(PosProperties pos, VendorKey vendorKey, MachineIdentity machine, JdbcTemplate jdbc) {
        this.file = pos.dataDir().resolve(FILE_NAME);
        this.vendorKey = vendorKey;
        this.machine = machine;
        this.jdbc = jdbc;
    }

    public LicenseStatus status() {
        return evaluate(installed());
    }

    /** @throws LicenseLockedException when this till may not issue bills, credit notes or edits */
    public void requireBilling() {
        LicenseStatus status = status();
        if (!status.billingAllowed()) {
            throw new LicenseLockedException(status.message());
        }
    }

    /**
     * Installs a new or renewed licence key, replacing the current one. Refuses a key that would not
     * let this till bill: another machine's, a forged one, or one whose grace period is already over.
     */
    public synchronized LicenseStatus activate(String key) {
        LicensePayload licence;
        try {
            licence = LicenseToken.read(key, vendorKey.publicKey());
        } catch (InvalidLicenseException e) {
            throw new FieldRejectedException("key", e.getMessage());
        }
        String here = machine.machineId();
        if (!licence.machineId().equals(here)) {
            throw new FieldRejectedException("key", "is for machine %s, not this one (%s)".formatted(licence.machineId(), here));
        }
        LicenseStatus would = evaluate(new Installed(licence, null));
        if (!would.billingAllowed()) {
            throw new FieldRejectedException("key", "ended on %s, including its grace period"
                    .formatted(DateTimeFormatter.ofPattern("dd-MM-yyyy").format(licence.graceEndsOn())));
        }
        try {
            Path written = Files.writeString(file.resolveSibling(FILE_NAME + ".new"), LicenseToken.normalize(key) + "\n");
            Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the licence to " + file, e);
        }
        installed = new Installed(licence, null);
        log.info("Licence {} for {} ({}) installed on machine {}: valid until {}, billing until {}",
                licence.licenseId(), licence.customer(), licence.customerId(), here, licence.validUntil(),
                licence.graceEndsOn());
        return evaluate(installed);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStatusAtStartup() {
        LicenseStatus status = status();
        if (status.state() == LicenseState.ACTIVE) {
            log.info("Licence: {}", status.message());
        } else {
            log.warn("Licence {}: {} (machine ID {})", status.state(), status.message(), status.machineId());
        }
    }

    private LicenseStatus evaluate(Installed licence) {
        MachineReading reading;
        try {
            reading = new MachineReading(machine.machineId(), null);
        } catch (IllegalStateException e) {
            reading = new MachineReading(null, e.getMessage());
        }
        long now = clock.millis();
        long latest = latestActivityBefore(now);
        LocalDate lastActivity = now + CLOCK_TOLERANCE_MILLIS < latest
                ? Instant.ofEpochMilli(latest).atZone(IndiaTime.ZONE).toLocalDate()
                : null;
        return LicensePolicy.evaluate(licence, reading, Instant.ofEpochMilli(now).atZone(IndiaTime.ZONE).toLocalDate(),
                lastActivity);
    }

    /**
     * The latest moment recorded before {@code now}, then {@code now} is remembered too. The audit trail
     * is append-only, so its newest timestamp survives a restart and cannot be wound back with the clock.
     */
    private long latestActivityBefore(long now) {
        long known = latestActivity.get();
        if (known < 0) {
            known = recordedActivity();
        }
        latestActivity.accumulateAndGet(Math.max(known, now), Math::max);
        return known;
    }

    private long recordedActivity() {
        try {
            Long latest = jdbc.queryForObject("SELECT COALESCE(MAX(timestamp), 0) FROM audit_revision", Long.class);
            return latest == null ? 0 : latest;
        } catch (DataAccessException e) {
            // A brand new database before its first revision.
            return 0;
        }
    }

    private Installed installed() {
        Installed current = installed;
        if (current == null) {
            current = load();
            installed = current;
        }
        return current;
    }

    private Installed load() {
        if (Files.notExists(file)) {
            return Installed.NONE;
        }
        try {
            return new Installed(LicenseToken.read(Files.readString(file), vendorKey.publicKey()), null);
        } catch (InvalidLicenseException e) {
            return new Installed(null, e.getMessage());
        } catch (IOException e) {
            return new Installed(null, "could not be read (%s)".formatted(e.getMessage()));
        }
    }
}
