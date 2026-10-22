package com.masspos.backup;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Where the till keeps a copy of the shop that uninstalling cannot reach, and how many to keep.
 *
 * @param offsiteDir a folder outside the installed one. The default sits beside it, under
 *                   {@code %LOCALAPPDATA%}; point it at a pen drive or a synced folder and the
 *                   shop has an off-machine copy as well.
 * @param keep       how many copies to leave there before the oldest is deleted
 */
@Validated
@ConfigurationProperties(prefix = "pos.backup")
public record BackupProperties(@NotNull Path offsiteDir, @Min(1) int keep) {
}
