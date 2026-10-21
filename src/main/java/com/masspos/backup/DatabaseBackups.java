package com.masspos.backup;

import com.masspos.common.IndiaTime;
import com.masspos.config.PosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Copies of the shop's database, taken and put back from the settings screen.
 *
 * <p>A backup is written with SQLite's {@code VACUUM INTO}, which makes a complete, checkpointed
 * copy while the till carries on billing - no stopping, and no half-written file if the power goes.
 *
 * <p>Putting one back cannot be done while the database is open, so it is <em>staged</em>: the file
 * is checked, today's data is backed up first, and the swap happens on the next start, before
 * anything opens the database. That way a restore either happens completely or not at all.
 */
@Service
public class DatabaseBackups {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackups.class);

    /** Matches the file name in {@code spring.datasource.url}. */
    static final String DATABASE = "pos.db";
    static final String PENDING = "restore-pending.db";
    static final String FOLDER = "backups";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** A file without these is not this shop's till database. */
    private static final List<String> EXPECTED_TABLES = List.of("invoice", "product", "shop_settings");

    private final JdbcTemplate jdbc;
    private final Path dataDir;

    public DatabaseBackups(JdbcTemplate jdbc, PosProperties pos) {
        this.jdbc = jdbc;
        this.dataDir = pos.dataDir();
    }

    /**
     * @param takenAt when the copy was made, from the file itself
     * @param restorePending true for the one the till will put back on the next start
     */
    public record BackupFile(String name, long sizeBytes, Instant takenAt, boolean restorePending) {
    }

    public Path folder() {
        return dataDir.resolve(FOLDER);
    }

    /** A complete copy, taken without stopping the till. */
    public BackupFile backUpNow() {
        return backUpAs("pos-" + STAMP.format(ZonedDateTime.now(IndiaTime.ZONE)));
    }

    public List<BackupFile> list() {
        if (!Files.isDirectory(folder())) {
            return List.of();
        }
        Optional<String> pending = pendingRestoreSource();
        try (Stream<Path> files = Files.list(folder())) {
            List<BackupFile> found = new ArrayList<>(files
                    .filter(file -> file.getFileName().toString().endsWith(".db"))
                    .map(file -> describe(file, pending))
                    .toList());
            found.sort(Comparator.comparing(BackupFile::takenAt).reversed());
            return List.copyOf(found);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + folder(), e);
        }
    }

    public Optional<String> pendingRestoreSource() {
        Path marker = dataDir.resolve(PENDING + ".from");
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(marker).trim());
        } catch (IOException e) {
            return Optional.of("a backup");
        }
    }

    /**
     * Lines a backup up to be put back on the next start. Today's data is copied first, so the
     * decision is never one-way.
     *
     * @throws IllegalArgumentException if the name is not one of this till's backups, or the file
     *                                  is not a database this till can read
     */
    public BackupFile stageRestore(String name) {
        Path chosen = folder().resolve(name).normalize();
        if (!chosen.getParent().equals(folder().normalize()) || !Files.isRegularFile(chosen)) {
            throw new IllegalArgumentException("There is no backup called \"" + name + "\" in " + folder());
        }
        refuseUnlessATillDatabase(chosen);
        BackupFile safety = backUpAs("before-restore-" + STAMP.format(ZonedDateTime.now(IndiaTime.ZONE)));
        try {
            Files.copy(chosen, dataDir.resolve(PENDING), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(dataDir.resolve(PENDING + ".from"), name);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stage " + name + " for restoring", e);
        }
        log.warn("Restore staged: {} will replace the database on the next start. Today's data is in {}",
                name, safety.name());
        return safety;
    }

    /** Changed their mind: the staged file is dropped and nothing is replaced. */
    public void cancelRestore() {
        try {
            Files.deleteIfExists(dataDir.resolve(PENDING));
            Files.deleteIfExists(dataDir.resolve(PENDING + ".from"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot cancel the staged restore", e);
        }
    }

    /**
     * Swaps a staged backup in. Called before anything opens the database, so the old file, its
     * write-ahead log and its shared-memory file all go together: a leftover WAL would be replayed
     * onto the restored database and corrupt it.
     */
    public static void applyPendingRestore(Path dataDir) {
        Path pending = dataDir.resolve(PENDING);
        if (!Files.isRegularFile(pending)) {
            return;
        }
        Path live = dataDir.resolve(DATABASE);
        String stamp = STAMP.format(ZonedDateTime.now(IndiaTime.ZONE));
        try {
            Files.createDirectories(dataDir.resolve(FOLDER));
            if (Files.exists(live)) {
                Files.move(live, dataDir.resolve(FOLDER).resolve("replaced-" + stamp + ".db"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(dataDir.resolve(DATABASE + "-wal"));
            Files.deleteIfExists(dataDir.resolve(DATABASE + "-shm"));
            Files.move(pending, live, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(dataDir.resolve(PENDING + ".from"));
            log.warn("Restored the database from a backup. The database it replaced is in {}",
                    dataDir.resolve(FOLDER).resolve("replaced-" + stamp + ".db"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot put the staged backup in place. The till has been left"
                    + " on its own database; " + pending + " is still there.", e);
        }
    }

    private BackupFile backUpAs(String baseName) {
        try {
            Files.createDirectories(folder());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + folder(), e);
        }
        Path file = folder().resolve(baseName + ".db");
        for (int attempt = 2; Files.exists(file); attempt++) {
            file = folder().resolve(baseName + "-" + attempt + ".db");
        }
        // VACUUM INTO refuses an existing file, which is why the name is made unique first.
        jdbc.execute("VACUUM INTO '" + file.toAbsolutePath().toString().replace("'", "''") + "'");
        return describe(file, Optional.empty());
    }

    private static BackupFile describe(Path file, Optional<String> pending) {
        try {
            String name = file.getFileName().toString();
            return new BackupFile(name, Files.size(file), Files.getLastModifiedTime(file).toInstant(),
                    pending.filter(name::equals).isPresent());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static void refuseUnlessATillDatabase(Path file) {
        List<String> tables = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException(file.getFileName() + " is not a database file this till can read");
        }
        List<String> missing = EXPECTED_TABLES.stream().filter(table -> !tables.contains(table)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(file.getFileName() + " is a database, but not one of this till's:"
                    + " it has no " + String.join(" or ", missing) + " table");
        }
    }
}
