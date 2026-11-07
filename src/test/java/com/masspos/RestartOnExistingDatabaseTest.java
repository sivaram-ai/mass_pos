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

    /**
     * The upgrade this release puts a shop through: a till that numbered its bills by the financial
     * year, {@code T1-2627-00123}, starts numbering them by the day, {@code T1-071126-0001}. The
     * shop takes a backup, installs, puts the backup back, and has to carry on billing.
     *
     * <p>Two things could have broken it, and neither may. The day's counters live in tables that
     * the old database has never seen, so they have to be created on an existing schema - SQLite
     * takes a new table on upgrade, which is why the day's count went into tables of its own rather
     * than into a changed unique constraint, which SQLite cannot alter on a populated table. And
     * the bills already taken keep their old numbers, so they must not collide with the new ones
     * nor stop reading.
     */
    @Test
    void aTillNumberingByTheYearUpgradesToNumberingByTheDay() throws SQLException {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
        try (ConfigurableApplicationContext released = start()) {
            assertThat(released.isRunning()).isTrue();
        }

        // Back to the previous release: no day counters, and a bill numbered by the financial year.
        String cashierId = UUID.randomUUID().toString();
        try (Connection db = open(); Statement sql = db.createStatement()) {
            sql.execute("DROP TABLE invoice_daily_sequence");
            sql.execute("DROP TABLE credit_note_daily_sequence");
            sql.execute("INSERT INTO app_user (id, version, created_at, updated_at, username, display_name,"
                    + " pin_hash, role, active, must_change_pin)"
                    + " VALUES ('" + cashierId + "', 0, 1, 1, 'upg-cashier', 'Asha K', 'hash', 'CASHIER', 1, 0)");
            sql.execute("INSERT INTO invoice_sequence (id, version, created_at, updated_at, terminal_code,"
                    + " financial_year, last_issued)"
                    + " VALUES ('" + UUID.randomUUID() + "', 0, 1, 1, 'T1', '2627', 123)");
            sql.execute("INSERT INTO invoice (id, version, created_at, updated_at, invoice_number, terminal_code,"
                    + " financial_year, sequence_no, invoice_date, issued_at, status, place_of_supply,"
                    + " seller_legal_name, seller_trade_name, seller_gstin, seller_state_code, seller_address,"
                    + " taxable_value_paise, cgst_paise, sgst_paise, igst_paise, cess_paise, round_off_paise,"
                    + " grand_total_paise, parcel, table_no, supplier_id, cashier_id)"
                    + " VALUES ('" + UUID.randomUUID() + "', 0, 1, 1, 'T1-2627-00123', 'T1', '2627', 123,"
                    + " '2026-11-06', 1, 'ISSUED', '27', 'Mass Retail Private Limited', 'MASS MART',"
                    + " '27AAPFU0939F1ZV', '27', '12 MG Road', 10000, 0, 0, 0, 0, 0, 10000, 0, '', '',"
                    + " '" + cashierId + "')");
        }

        try (ConfigurableApplicationContext upgraded = start()) {
            assertThat(upgraded.isRunning()).isTrue();
        }

        try (Connection db = open(); Statement sql = db.createStatement()) {
            // The day's counters are there, and empty: the next bill of the day is number 1.
            try (ResultSet row = sql.executeQuery("SELECT count(*) FROM invoice_daily_sequence")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isZero();
            }
            try (ResultSet row = sql.executeQuery("SELECT count(*) FROM credit_note_daily_sequence")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isZero();
            }
            // The bill taken under the old series is untouched and still reads.
            try (ResultSet row = sql.executeQuery(
                    "SELECT invoice_number, sequence_no FROM invoice WHERE invoice_number = 'T1-2627-00123'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("T1-2627-00123");
                assertThat(row.getLong(2)).isEqualTo(123);
            }
            // The year's counter carries on from where the shop left off, so the books stay gapless.
            try (ResultSet row = sql.executeQuery(
                    "SELECT last_issued FROM invoice_sequence WHERE terminal_code = 'T1'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(123);
            }
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
