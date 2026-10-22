package com.masspos.config;

import com.masspos.desktop.PosPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Brings a till installed before 0.1.2 into the single-folder layout. Until then the program was
 * installed in one folder and the shop's data lived in another ({@code %LOCALAPPDATA%\MassPOS});
 * now both are under {@code %LOCALAPPDATA%\GoRockitPOS}, the data in its {@code data} subfolder.
 *
 * <p>It runs from {@code main}, before Spring starts. By the time any bean exists, logging has
 * already created {@code logs/pos.log} in the new directory and the datasource is about to create
 * {@code pos.db} there, so a till with bills in the old folder would quietly open an empty shop.
 * That is the one outcome worth refusing to start over, so a failure here throws.
 *
 * <p>Nothing is copied: the entries are renamed one by one within the same drive, and the old
 * folder is removed once it is empty. A till that has already moved, or that never had an old
 * folder, is left alone, as is one whose data directory was chosen with {@code POS_DATA_DIR}.
 */
public final class DataDirMigration {

    private static volatile String report;

    private DataDirMigration() {
    }

    /** Moves the old data folder into the new one if this machine still has one. */
    public static void migrateIfNeeded() {
        if (System.getenv("POS_DATA_DIR") != null && !System.getenv("POS_DATA_DIR").isBlank()) {
            return;
        }
        migrate(PosPaths.legacyDataDir(), PosPaths.dataDir());
    }

    /**
     * @return what was moved, for the log, or null when there was nothing to do
     */
    static String migrate(Path from, Path to) {
        report = null;
        if (Files.notExists(from.resolve("pos.db")) || Files.exists(to.resolve("pos.db"))) {
            return null;
        }
        try {
            Files.createDirectories(to);
            List<String> moved = new ArrayList<>();
            try (Stream<Path> entries = Files.list(from)) {
                for (Path entry : entries.toList()) {
                    Path target = to.resolve(entry.getFileName().toString());
                    if (Files.exists(target)) {
                        continue; // logs/ and the like may already be there; never overwrite
                    }
                    Files.move(entry, target);
                    moved.add(entry.getFileName().toString());
                }
            }
            deleteIfEmpty(from);
            report = "Moved this till's data from %s into %s: %s".formatted(from, to, String.join(", ", moved));
            return report;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot move this till's data from " + from + " to " + to
                            + ". The shop's database is still in the old folder; move it by hand or"
                            + " set POS_DATA_DIR to the old folder, rather than billing on an empty one.", e);
        }
    }

    /** What the migration did, once, for whoever reads pos.log after an upgrade. */
    public static String report() {
        return report;
    }

    private static void deleteIfEmpty(Path folder) throws IOException {
        try (Stream<Path> left = Files.list(folder)) {
            if (left.findAny().isEmpty()) {
                Files.delete(folder);
            }
        }
    }
}
