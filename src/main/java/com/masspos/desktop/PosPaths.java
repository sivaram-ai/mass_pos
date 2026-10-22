package com.masspos.desktop;

import java.nio.file.Path;

/**
 * Where a till keeps its program and its data, in one place.
 *
 * <p>The desktop launcher is packaged in its own jar, without Spring on the classpath, so it cannot
 * ask {@code PosProperties} where the database lives; it has to work the same default out for
 * itself. Both it and {@link com.masspos.config.DataDirMigration} read it from here so there is one
 * definition to keep in step with {@code pos.data-dir} in application.properties.
 */
public final class PosPaths {

    /** Installed program folder and data folder share this name, so a till is one folder. */
    public static final String APP_FOLDER = "GoRockitPOS";

    /** Data sits in a subfolder, so an uninstall that removes the program's files leaves it behind. */
    public static final String DATA_FOLDER = "data";

    /** What the shop was before 0.1.2: a data folder of its own, beside the installed program. */
    public static final String LEGACY_DATA_FOLDER = "MassPOS";

    public static final int DEFAULT_PORT = 8765;

    private PosPaths() {
    }

    /** {@code %LOCALAPPDATA%} on Windows, the home directory anywhere else. */
    public static Path base() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"))
                : Path.of(localAppData);
    }

    /**
     * The data directory this machine would use with nothing configured. {@code POS_DATA_DIR} wins
     * when it is set, exactly as application.properties resolves it.
     */
    public static Path dataDir() {
        String configured = System.getenv("POS_DATA_DIR");
        return configured == null || configured.isBlank()
                ? base().resolve(APP_FOLDER).resolve(DATA_FOLDER)
                : Path.of(configured);
    }

    /** The folder tills used for data before the program and the data were brought together. */
    public static Path legacyDataDir() {
        return base().resolve(LEGACY_DATA_FOLDER);
    }
}
