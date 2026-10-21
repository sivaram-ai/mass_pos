package com.masspos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

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

    /**
     * The upgrade a shop actually lives through: a new release adds a column to a table that already
     * holds the shop's rows. SQLite refuses to add a NOT NULL column to a populated table unless the
     * column carries a default, so every such column must declare one (see Product.billWithoutStock).
     */
    @Test
    void aNewColumnReachesATableThatAlreadyHasRows() throws SQLException {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
        try (ConfigurableApplicationContext released = start()) {
            assertThat(released.isRunning()).isTrue();
        }
        // Back to the previous release's schema, with a product on the shelf.
        try (Connection db = open(); Statement sql = db.createStatement()) {
            sql.execute("ALTER TABLE product DROP COLUMN bill_without_stock");
            sql.execute("INSERT INTO product (id, version, created_at, updated_at, sku, name, hsn_code, unit,"
                    + " mrp_paise, selling_price_paise, tax_inclusive, gst_rate_bp, cess_rate_bp, active)"
                    + " VALUES ('" + UUID.randomUUID() + "', 0, 1, 1, 'UPG-1', 'Sugar 1kg', '', 'KGS',"
                    + " 5000, 5000, 1, 500, 0, 1)");
        }

        try (ConfigurableApplicationContext upgraded = start()) {
            assertThat(upgraded.isRunning()).isTrue();
        }

        try (Connection db = open(); Statement sql = db.createStatement();
             ResultSet row = sql.executeQuery("SELECT bill_without_stock FROM product WHERE sku = 'UPG-1'")) {
            assertThat(row.next()).isTrue();
            // The shelf item keeps the old behaviour: it is still counted.
            assertThat(row.getBoolean(1)).isFalse();
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + DATA_DIR + "/pos.db");
    }

    /** Command-line arguments outrank application.properties, so a till already on 8765 is left alone. */
    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(PosApplication.class)
                .run("--pos.data-dir=" + DATA_DIR, "--server.port=0", "--pos.printer.type=FILE");
    }
}
