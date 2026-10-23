package com.masspos.backup;

import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.config.PosProperties;
import com.masspos.settings.ShopSettings;
import com.masspos.settings.ShopSettingsService;
import com.masspos.terminal.TerminalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The copy that outlives the program. Uninstalling takes the till's folder and everything in it,
 * so what is written here is the shop's last line of defence.
 */
class OffsiteBackupsTest {

    @Test
    void keepsTheDatabaseAndTheLicenceWhereUninstallingCannotReachThem(@TempDir Path base) {
        Path dataDir = base.resolve("GoRockitPOS").resolve("data");
        Path offsite = base.resolve("GoRockitPOS-backups");
        Path startStop = offsite.resolve("start-stop");
        OffsiteBackups till = till(dataDir, offsite, 10);

        till.copy("start");

        assertThat(databasesIn(startStop)).hasSize(1);
        assertThat(startStop.resolve("pos.properties")).hasContent("pos.terminal.code=T1");
        assertThat(startStop.resolve("license.lic")).hasContent("MPL1.signed");
        // A .db needs this program to read; the sheets beside it open in Excel on any machine.
        assertThat(sheetsIn(startStop, "-products.csv")).singleElement()
                .satisfies(sheet -> assertThat(sheet).content().contains("SUG-1KG").contains("Sugar loose"));
        assertThat(sheetsIn(startStop, "-settings.csv")).singleElement()
                .satisfies(sheet -> assertThat(sheet).content().contains("Legal name"));
        assertThat(offsite.startsWith(dataDir.getParent())).as("outside the installed folder").isFalse();
    }

    @Test
    void theOldestCopiesGoWhenThereAreMoreThanTheTillKeeps(@TempDir Path base) throws IOException {
        Path dataDir = base.resolve("GoRockitPOS").resolve("data");
        Path offsite = base.resolve("GoRockitPOS-backups");
        Path startStop = offsite.resolve("start-stop");
        OffsiteBackups till = till(dataDir, offsite, 2);
        Files.createDirectories(startStop);
        for (int age = 1; age <= 3; age++) {
            Path old = startStop.resolve("pos-old-" + age + ".db");
            Files.writeString(old, "older copy");
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(age, ChronoUnit.DAYS)));
        }

        till.copy("stop");

        assertThat(databasesIn(startStop)).as("today's copy and yesterday's, nothing older").hasSize(2);
        assertThat(startStop.resolve("pos-old-3.db")).doesNotExist();
        assertThat(startStop.resolve("pos-old-1.db")).exists();
    }

    /**
     * Opening and closing a till prunes; a copy somebody took on purpose must not be what gets
     * pruned. That is the whole reason the two kinds are in folders of their own.
     */
    @Test
    void aCopyTakenByHandIsNotPrunedByTheTillOpeningAndClosing(@TempDir Path base) throws IOException {
        Path dataDir = base.resolve("GoRockitPOS").resolve("data");
        Path offsite = base.resolve("GoRockitPOS-backups");
        OffsiteBackups till = till(dataDir, offsite, 1);
        Path kept = offsite.resolve("backups").resolve("pos-20260101-120000.db");
        Files.createDirectories(kept.getParent());
        Files.writeString(kept, "the copy the owner took before the price rise");
        Files.setLastModifiedTime(kept, FileTime.from(Instant.now().minus(300, ChronoUnit.DAYS)));

        till.copy("start");
        till.copy("stop");

        assertThat(kept).exists();
        assertThat(databasesIn(offsite.resolve("start-stop"))).hasSize(1);
    }

    /** A till set up before the split keeps its copies: they are moved out, not left to be deleted. */
    @Test
    void bringsTheCopiesOfAnOlderTillOutOfTheFolderAnUninstallDeletes(@TempDir Path base) throws IOException {
        Path dataDir = base.resolve("GoRockitPOS").resolve("data");
        Path offsite = base.resolve("GoRockitPOS-backups");
        OffsiteBackups till = till(dataDir, offsite, 10);
        Files.createDirectories(dataDir.resolve("backups"));
        Files.writeString(dataDir.resolve("backups").resolve("pos-20260501-101500.db"), "last May");

        till.run(null);

        assertThat(offsite.resolve("backups").resolve("pos-20260501-101500.db")).hasContent("last May");
        assertThat(dataDir.resolve("backups").resolve("pos-20260501-101500.db")).doesNotExist();
    }

    /**
     * The automatic copies used to sit loose in the root of the offsite folder. Nothing lists the
     * root any more, so a till being replaced would stop being offered the newest thing it has.
     */
    @Test
    void bringsTheLooseCopiesOfAnOlderTillIntoTheStartStopFolder(@TempDir Path base) throws IOException {
        Path dataDir = base.resolve("GoRockitPOS").resolve("data");
        Path offsite = base.resolve("GoRockitPOS-backups");
        OffsiteBackups till = till(dataDir, offsite, 10);
        Files.createDirectories(offsite);
        Files.writeString(offsite.resolve("pos-20260501-200000-stop.db"), "the night it was last closed");

        till.run(null);

        assertThat(offsite.resolve("start-stop").resolve("pos-20260501-200000-stop.db"))
                .hasContent("the night it was last closed");
        assertThat(offsite.resolve("pos-20260501-200000-stop.db")).doesNotExist();
    }

    @Test
    void aFolderItCannotWriteIsLoggedAndTheTillCarriesOn(@TempDir Path base) throws IOException {
        Path dataDir = base.resolve("GoRockitPOS").resolve("data");
        Path blocked = base.resolve("a-file-not-a-folder");
        Files.writeString(blocked, "in the way");
        OffsiteBackups till = till(dataDir, blocked.resolve("backups"), 10);

        assertThatCode(() -> till.copy("start")).doesNotThrowAnyException();
        assertThat(dataDir.resolve("pos.db")).as("the shop's own database is untouched").exists();
    }

    private static OffsiteBackups till(Path dataDir, Path offsite, int keep) {
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve("pos.properties"), "pos.terminal.code=T1");
            Files.writeString(dataDir.resolve("license.lic"), "MPL1.signed");
            JdbcTemplate jdbc = new JdbcTemplate(
                    new DriverManagerDataSource("jdbc:sqlite:" + dataDir.resolve("pos.db")));
            jdbc.execute("CREATE TABLE IF NOT EXISTS invoice (id TEXT)");
            PosProperties pos = new PosProperties(dataDir,
                    new PosProperties.Terminal("T1", "Counter 1", "", TerminalType.COUNTER));
            ProductRepository catalogue = mock(ProductRepository.class);
            when(catalogue.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(
                    new Product("SUG-1KG", "Sugar loose", "1701", UnitOfMeasure.KGS, 5_200, 5_500, true, 500)));
            ShopSettingsService shop = mock(ShopSettingsService.class);
            when(shop.current()).thenReturn(new ShopSettings());
            BackupProperties where = new BackupProperties(offsite, keep);
            return new OffsiteBackups(new DatabaseBackups(jdbc, pos, catalogue, shop, where), pos, where);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Stream<Path> sheetsIn(Path folder, String suffix) {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(suffix)).toList().stream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Stream<Path> databasesIn(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".db")).toList().stream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
