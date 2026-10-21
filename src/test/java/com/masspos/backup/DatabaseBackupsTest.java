package com.masspos.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The swap itself: it runs before anything opens the database, so it has only files to work with
 * and no second chance.
 */
class DatabaseBackupsTest {

    @Test
    void putsTheStagedBackupInPlaceAndKeepsWhatItReplaced(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("pos.db"), "today's database");
        Files.writeString(dataDir.resolve("restore-pending.db"), "last week's database");
        Files.writeString(dataDir.resolve("restore-pending.db.from"), "pos-20261014-090000.db");

        DatabaseBackups.applyPendingRestore(dataDir);

        assertThat(dataDir.resolve("pos.db")).hasContent("last week's database");
        assertThat(dataDir.resolve("restore-pending.db")).doesNotExist();
        assertThat(dataDir.resolve("restore-pending.db.from")).as("the marker goes with it").doesNotExist();
        assertThat(replaced(dataDir)).singleElement()
                .satisfies(file -> assertThat(file).hasContent("today's database"));
    }

    /**
     * A write-ahead log belongs to the database that wrote it. Left behind, SQLite would replay it
     * onto the restored file and wreck it, so both it and the shared-memory file go.
     */
    @Test
    void takesTheWriteAheadLogWithTheDatabaseItBelongsTo(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("pos.db"), "today");
        Files.writeString(dataDir.resolve("pos.db-wal"), "uncheckpointed writes");
        Files.writeString(dataDir.resolve("pos.db-shm"), "shared memory");
        Files.writeString(dataDir.resolve("restore-pending.db"), "last week");

        DatabaseBackups.applyPendingRestore(dataDir);

        assertThat(dataDir.resolve("pos.db-wal")).doesNotExist();
        assertThat(dataDir.resolve("pos.db-shm")).doesNotExist();
        assertThat(dataDir.resolve("pos.db")).hasContent("last week");
    }

    @Test
    void doesNothingAtAllWhenNoRestoreIsWaiting(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("pos.db"), "today's database");

        DatabaseBackups.applyPendingRestore(dataDir);

        assertThat(dataDir.resolve("pos.db")).hasContent("today's database");
        assertThat(dataDir.resolve("backups")).doesNotExist();
    }

    /** A first start has no database yet: a backup can still be put in before one is made. */
    @Test
    void restoresOntoATillThatHasNoDatabaseYet(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("restore-pending.db"), "another till's data");

        DatabaseBackups.applyPendingRestore(dataDir);

        assertThat(dataDir.resolve("pos.db")).hasContent("another till's data");
        assertThat(replaced(dataDir)).isEmpty();
    }

    private static List<Path> replaced(Path dataDir) throws IOException {
        Path folder = dataDir.resolve("backups");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().startsWith("replaced-")).toList();
        }
    }
}
