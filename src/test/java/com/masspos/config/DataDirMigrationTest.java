package com.masspos.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataDirMigrationTest {

    @Test
    void aTillUpgradedFromTheTwoFolderLayoutTakesItsShopWithIt(@TempDir Path base) throws IOException {
        Path old = oldTill(base, "bills and all");

        String report = DataDirMigration.migrate(old, base.resolve("GoRockitPOS").resolve("data"));

        Path moved = base.resolve("GoRockitPOS").resolve("data");
        assertThat(moved.resolve("pos.db")).hasContent("bills and all");
        assertThat(moved.resolve("license.lic")).exists();
        assertThat(moved.resolve("logs").resolve("pos.log")).exists();
        assertThat(old).doesNotExist();
        assertThat(report).contains("pos.db");
        assertThat(DataDirMigration.report()).isEqualTo(report);
    }

    @Test
    void aTillThatHasAlreadyMovedIsLeftExactlyAsItIs(@TempDir Path base) throws IOException {
        Path old = oldTill(base, "yesterday");
        Path now = base.resolve("GoRockitPOS").resolve("data");
        Files.createDirectories(now);
        Files.writeString(now.resolve("pos.db"), "today");

        assertThat(DataDirMigration.migrate(old, now)).isNull();
        assertThat(now.resolve("pos.db")).hasContent("today");
        assertThat(old.resolve("pos.db")).hasContent("yesterday");
    }

    @Test
    void aFreshInstallHasNothingToMoveAndCreatesNothing(@TempDir Path base) {
        Path now = base.resolve("GoRockitPOS").resolve("data");

        assertThat(DataDirMigration.migrate(base.resolve("MassPOS"), now)).isNull();
        assertThat(now).doesNotExist();
    }

    @Test
    void whateverTheNewFolderAlreadyHoldsIsNeverWrittenOver(@TempDir Path base) throws IOException {
        Path old = oldTill(base, "the shop");
        Path now = base.resolve("GoRockitPOS").resolve("data");
        Files.createDirectories(now.resolve("logs"));
        Files.writeString(now.resolve("logs").resolve("pos.log"), "this run");

        DataDirMigration.migrate(old, now);

        assertThat(now.resolve("pos.db")).hasContent("the shop");
        assertThat(now.resolve("logs").resolve("pos.log")).hasContent("this run");
        // What could not be merged stays in the old folder rather than being lost.
        assertThat(old.resolve("logs").resolve("pos.log")).hasContent("older run");
    }

    private static Path oldTill(Path base, String database) throws IOException {
        Path old = base.resolve("MassPOS");
        Files.createDirectories(old.resolve("logs"));
        Files.writeString(old.resolve("pos.db"), database);
        Files.writeString(old.resolve("license.lic"), "MPL1.x.y");
        Files.writeString(old.resolve("logs").resolve("pos.log"), "older run");
        return old;
    }
}
