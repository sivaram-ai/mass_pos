package com.masspos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.FileSystemUtils;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A till is started every morning on the database it made the day before. Every other test starts
 * on an empty database, which hides problems that only show when Hibernate reads an existing schema,
 * such as SQLite's limit of 500 terms in the column metadata query once the schema has grown.
 */
class RestartOnExistingDatabaseTest {

    private static final String DATA_DIR = "target/test-data/restart-it";

    @Test
    void theAppStartsAgainOnTheDatabaseItCreated() {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));

        try (ConfigurableApplicationContext first = start()) {
            assertThat(first.isRunning()).isTrue();
        }
        try (ConfigurableApplicationContext second = start()) {
            assertThat(second.isRunning()).isTrue();
        }
    }

    /** Command-line arguments outrank application.properties, so a till already on 8765 is left alone. */
    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(PosApplication.class)
                .run("--pos.data-dir=" + DATA_DIR, "--server.port=0", "--pos.printer.type=FILE");
    }
}
