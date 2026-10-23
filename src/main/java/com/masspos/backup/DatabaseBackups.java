package com.masspos.backup;

import com.masspos.catalog.ProductCsv;
import com.masspos.common.Csv;
import com.masspos.catalog.ProductRepository;
import com.masspos.common.IndiaTime;
import com.masspos.config.PosProperties;
import com.masspos.settings.ShopSettingsCsv;
import com.masspos.settings.ShopSettingsForm;
import com.masspos.settings.ShopSettingsService;
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
import java.util.function.Supplier;
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
    private final ProductRepository products;
    private final ShopSettingsService settings;
    private final BackupProperties where;

    public DatabaseBackups(JdbcTemplate jdbc, PosProperties pos, ProductRepository products,
                           ShopSettingsService settings, BackupProperties where) {
        this.jdbc = jdbc;
        this.dataDir = pos.dataDir();
        this.products = products;
        this.settings = settings;
        this.where = where;
    }

    /**
     * @param takenAt when the copy was made, from the file itself
     * @param restorePending true for the one the till will put back on the next start
     */
    public record BackupFile(String name, long sizeBytes, Instant takenAt, boolean restorePending) {
    }

    /**
     * Where copies taken by hand live. Outside the till's own folder, because that folder goes
     * with the program when it is uninstalled - which is the one moment a shop most needs them.
     * A till upgraded from the old layout has its copies moved across on the first start.
     */
    public Path folder() {
        return where.backupsDir();
    }

    /** A complete copy, taken without stopping the till. */
    public BackupFile backUpNow() {
        return backUpInto(folder(), "pos-" + STAMP.format(ZonedDateTime.now(IndiaTime.ZONE)));
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
        BackupFile safety = backUpInto(folder(), "before-restore-" + STAMP.format(ZonedDateTime.now(IndiaTime.ZONE)));
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

    /**
     * Lines up a copy that is not one of this till's own - one from the folder outside the
     * installed one, or a file handed over on a pen drive. It is checked the same way, copied in
     * so the next start finds it where it expects to, and then staged like any other.
     *
     * @param label what to call it once it is here, for the list and the log
     */
    public BackupFile stageRestoreFrom(Path source, String label) {
        refuseUnlessATillDatabase(source);
        String name = label.replaceAll("[^A-Za-z0-9._-]", "-");
        if (!name.endsWith(".db")) {
            name = name + ".db";
        }
        try {
            Files.createDirectories(folder());
            Path landed = folder().resolve("brought-in-" + STAMP.format(ZonedDateTime.now(IndiaTime.ZONE))
                    + "-" + name);
            Files.copy(source, landed, StandardCopyOption.REPLACE_EXISTING);
            return stageRestore(landed.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + source, e);
        }
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
    public static void applyPendingRestore(Path dataDir, Path backupFolder) {
        Path pending = dataDir.resolve(PENDING);
        if (!Files.isRegularFile(pending)) {
            return;
        }
        Path live = dataDir.resolve(DATABASE);
        Path replaced = backupFolder.resolve("replaced-" + STAMP.format(ZonedDateTime.now(IndiaTime.ZONE)) + ".db");
        try {
            Files.createDirectories(backupFolder);
            if (Files.exists(live)) {
                Files.move(live, replaced, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(dataDir.resolve(DATABASE + "-wal"));
            Files.deleteIfExists(dataDir.resolve(DATABASE + "-shm"));
            Files.move(pending, live, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(dataDir.resolve(PENDING + ".from"));
            log.warn("Restored the database from a backup. The database it replaced is in {}", replaced);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot put the staged backup in place. The till has been left"
                    + " on its own database; " + pending + " is still there.", e);
        }
    }

    /**
     * Puts the copies of a till set up before the folders were split where they are looked for now.
     * Two moves, both one-time:
     *
     * <ul>
     *   <li>The ones taken by hand sat in {@code <data-dir>/backups}, inside the folder an
     *       uninstall deletes - the shop would have found them gone exactly when it went looking.
     *   <li>The automatic ones sat loose in the root of the offsite folder, which nothing lists
     *       any more, so the sign-in screen would stop offering them to a till being replaced.
     * </ul>
     *
     * <p>A name already taken at the far end is left where it is, and a move that fails is logged
     * rather than thrown: this is tidying, and the backup itself is already safely written.
     */
    void bringOldCopiesAcross() {
        moveAll(dataDir.resolve(FOLDER), folder());
        moveAll(where.offsiteDir().normalize(), where.startStopDir());
    }

    private void moveAll(Path from, Path to) {
        if (!Files.isDirectory(from) || from.normalize().equals(to.normalize())) {
            return;
        }
        try (Stream<Path> files = Files.list(from)) {
            List<Path> loose = files.filter(Files::isRegularFile).toList();
            if (loose.isEmpty()) {
                return;
            }
            Files.createDirectories(to);
            int moved = 0;
            for (Path file : loose) {
                Path landing = to.resolve(file.getFileName().toString());
                if (Files.exists(landing)) {
                    continue;
                }
                Files.move(file, landing);
                moved++;
            }
            if (moved > 0) {
                log.info("Moved {} backup file(s) from {} into {}, where this version looks for them",
                        moved, from, to);
            }
        } catch (IOException e) {
            log.warn("Could not move the older backups out of {}: {}", from, e.toString());
        }
    }

    /**
     * A complete copy in a folder of the caller's choosing, so that the copy kept outside the
     * till's own folder is taken the same safe way as the ones on the settings screen.
     */
    public BackupFile backUpInto(Path folder, String baseName) {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + folder, e);
        }
        Path file = folder.resolve(baseName + ".db");
        for (int attempt = 2; Files.exists(file); attempt++) {
            file = folder.resolve(baseName + "-" + attempt + ".db");
        }
        // VACUUM INTO refuses an existing file, which is why the name is made unique first.
        jdbc.execute("VACUUM INTO '" + file.toAbsolutePath().toString().replace("'", "''") + "'");
        writeSidecars(file);
        return describe(file, Optional.empty());
    }

    /**
     * The catalogue and the shop's own details, written beside the copy as the same sheets the
     * Products and Settings screens export.
     *
     * <p>A .db is all or nothing: to read one you need this program, and to use part of one you
     * need all of it. A shop that wants last month's price list, or to set a second till up the
     * way the first one is, can open these in Excel without restoring anything. Neither failing
     * loses the backup, which is the thing that matters and is already on disk by now.
     */
    private void writeSidecars(Path database) {
        String base = database.getFileName().toString().replaceFirst("\\.db$", "");
        write(database.resolveSibling(base + "-products.csv"),
                () -> ProductCsv.writeCatalogue(products.findAllByActiveTrueOrderByNameAsc()));
        write(database.resolveSibling(base + "-settings.csv"),
                () -> ShopSettingsCsv.write(ShopSettingsForm.of(settings.current())));
    }

    private static void write(Path file, Supplier<String> contents) {
        try {
            Files.writeString(file, Csv.EXCEL_BOM + contents.get());
        } catch (Exception e) {
            log.warn("Backup written, but {} was not: {}", file.getFileName(), e.toString());
        }
    }

    /** Newest first, for any listing of a folder of copies. */
    static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unreadable) {
            return 0;
        }
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
