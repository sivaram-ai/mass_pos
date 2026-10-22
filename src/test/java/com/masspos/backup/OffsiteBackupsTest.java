package com.masspos.backup;

import com.masspos.config.PosProperties;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The copy that outlives the program. Uninstalling takes the till's folder and everything in it,
 * so what is written here is the shop's last line of defence.
 */
class OffsiteBackupsTest {

    @Test
    void keepsTheDatabaseAndTheLicenceWhereUninstallingCannotReachThem(@TempDir Path base) {
        Path dataDir = base.resolve("GoRockitPOS").resolve("data");
        Path offsite = base.resolve("GoRockitPOS-backups");
        OffsiteBackups till = till(dataDir, offsite, 10);

        till.copy("start");

        assertThat(databasesIn(offsite)).hasSize(1);
        assertThat(offsite.resolve("pos.properties")).hasContent("pos.terminal.code=T1");
        assertThat(offsite.resolve("license.lic")).hasContent("MPL1.signed");
        assertThat(offsite.startsWith(dataDir.getParent())).as("outside the installed folder").isFalse();
    }

    @Test
    void theOldestCopiesGoWhenThereAreMoreThanTheTillKeeps(@TempDir Path base) throws IOException {
        Path dataDir = base.resolve("GoRockitPOS").resolve("data");
        Path offsite = base.resolve("GoRockitPOS-backups");
        OffsiteBackups till = till(dataDir, offsite, 2);
        Files.createDirectories(offsite);
        for (int age = 1; age <= 3; age++) {
            Path old = offsite.resolve("pos-old-" + age + ".db");
            Files.writeString(old, "older copy");
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(age, ChronoUnit.DAYS)));
        }

        till.copy("stop");

        assertThat(databasesIn(offsite)).as("today's copy and yesterday's, nothing older").hasSize(2);
        assertThat(offsite.resolve("pos-old-3.db")).doesNotExist();
        assertThat(offsite.resolve("pos-old-1.db")).exists();
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
            return new OffsiteBackups(new DatabaseBackups(jdbc, pos), pos, new BackupProperties(offsite, keep));
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
