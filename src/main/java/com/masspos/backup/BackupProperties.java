package com.masspos.backup;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Where the till keeps its copies of the shop, and how many of the automatic ones to keep.
 *
 * <p>Everything lives outside the installed folder, because uninstalling takes that folder and
 * everything under it. The two kinds are kept apart so that one never crowds the other out: the
 * till writes a copy into {@code start-stop} every time it opens and closes, and those are pruned
 * to {@code keep}; a copy taken by hand from Settings &gt; Database goes into {@code backups} and
 * stays until somebody deletes it.
 *
 * @param offsiteDir a folder outside the installed one. The default sits beside it, under
 *                   {@code %LOCALAPPDATA%}; point it at a pen drive or a synced folder and the
 *                   shop has an off-machine copy as well.
 * @param keep       how many automatic copies to leave before the oldest is deleted
 */
@Validated
@ConfigurationProperties(prefix = "pos.backup")
public record BackupProperties(@NotNull Path offsiteDir, @Min(1) int keep) {

    /** Written at every start and every clean shutdown, and pruned. */
    public Path startStopDir() {
        return offsiteDir().normalize().resolve("start-stop");
    }

    /** Taken by hand from the settings screen, and never deleted by the till. */
    public Path backupsDir() {
        return offsiteDir().normalize().resolve("backups");
    }
}
