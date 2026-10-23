package com.masspos.backup;

import com.masspos.common.IndiaTime;
import com.masspos.config.PosProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A copy of the shop kept outside the till's own folder.
 *
 * <p>Uninstalling deletes the installed folder and everything under it - the database, the
 * licence, the settings, and the backups in {@code data/backups} with them. Windows gives a
 * program installed from an MSI no way to run code while it is being removed, so the protection
 * cannot wait for that moment. Instead the till keeps an up-to-date copy somewhere the installer
 * does not own, written at every start and at every clean shutdown. An update is done with the
 * till closed, so the copy from when it was last closed is the one from before the update.
 *
 * <p>These go in their own {@code start-stop} folder, apart from the copies a person takes from
 * the settings screen. Pruning is what makes the split matter: a till that opens and closes twice
 * a day would otherwise push out the copy somebody deliberately kept.
 *
 * <p>A backup never stops a shop billing: a folder that cannot be written is logged and the till
 * carries on.
 */
@Component
public class OffsiteBackups implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OffsiteBackups.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Small files worth having beside the database: without these a restored till is not this till. */
    static final List<String> COMPANIONS = List.of("pos.properties", "license.lic");

    private final DatabaseBackups backups;
    private final Path dataDir;
    private final BackupProperties settings;

    public OffsiteBackups(DatabaseBackups backups, PosProperties pos, BackupProperties settings) {
        this.backups = backups;
        this.dataDir = pos.dataDir();
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        backups.bringOldCopiesAcross();
        copy("start");
    }

    @PreDestroy
    void onShutdown() {
        copy("stop");
    }

    /**
     * @param moment goes in the file name, so it is obvious whether a copy is from the morning's
     *               first bill or from the night the till was closed for an update
     */
    void copy(String moment) {
        Path folder = settings.startStopDir();
        try {
            DatabaseBackups.BackupFile written =
                    backups.backUpInto(folder, "pos-" + STAMP.format(ZonedDateTime.now(IndiaTime.ZONE)) + "-" + moment);
            for (String companion : COMPANIONS) {
                Path from = dataDir.resolve(companion);
                if (Files.isRegularFile(from)) {
                    Files.copy(from, folder.resolve(companion), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            prune(folder);
            log.info("Kept a copy of the shop in {} ({}), where uninstalling the program cannot reach it",
                    folder, written.name());
        } catch (Exception e) {
            log.warn("Could not keep a copy of the shop in {}: {}. The till is unaffected, but an"
                    + " uninstall would take the database with the program.", folder, e.toString());
        }
    }

    /** Oldest first out, so a till that has run for years is not a folder of thousands of copies. */
    private void prune(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> copies = files
                    .filter(file -> file.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparing(OffsiteBackups::modifiedAt).reversed())
                    .toList();
            for (Path old : copies.stream().skip(settings.keep()).toList()) {
                Files.deleteIfExists(old);
            }
        }
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
