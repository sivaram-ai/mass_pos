package com.masspos;

import com.masspos.audit.AuditActor;
import com.masspos.audit.AuditContext;
import com.masspos.audit.AuditRevision;
import com.masspos.billing.Invoice;
import com.masspos.billing.InvoiceSequence;
import com.masspos.billing.InvoiceStatus;
import com.masspos.catalog.Product;
import com.masspos.common.persistence.UuidV7;
import com.masspos.inventory.InventoryLedgerEvent;
import com.masspos.inventory.LedgerEventType;
import com.masspos.user.User;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.masspos.TestFixtures.newCashier;
import static com.masspos.TestFixtures.newInvoice;
import static com.masspos.TestFixtures.newProduct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"pos.data-dir=" + PersistenceIntegrationTest.DATA_DIR, "pos.terminal.code=T1"})
class PersistenceIntegrationTest {

    static final String DATA_DIR = "target/test-data/persistence-it";

    static {
        // Fresh database per run. The previous run's JVM has exited, so nothing holds the files.
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
    }

    @Autowired
    EntityManager em;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sqliteRunsInWalModeWithDurableImmediateTransactions() {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("wal");
        assertThat(jdbc.queryForObject("PRAGMA synchronous", Integer.class)).isEqualTo(2); // FULL
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(5000);
    }

    @Test
    void foreignKeysAreEnforced() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inventory_ledger_event (id, version, created_at, updated_at, product_id, type,"
                        + " quantity_delta_milli, terminal_code, occurred_at) VALUES (?, 0, 0, 0, ?, 'ADJUSTMENT', 1, 'T1', 0)",
                UuidV7.next().toString(), UuidV7.next().toString()))
                .hasMessageContaining("FOREIGN KEY");
    }

    @Test
    void primaryKeysAreUuidV7StoredAsText() {
        Product product = tx.execute(s -> persist(newProduct(5_000)));

        assertThat(product.getId().version()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT typeof(id) || ':' || length(id) FROM product WHERE id = ?",
                String.class, product.getId().toString())).isEqualTo("text:36");
    }

    @Test
    void preAssignedIdsFromSyncAreKept() {
        UUID remoteId = UuidV7.next();
        tx.executeWithoutResult(s -> {
            Product product = newProduct(5_000);
            product.assignId(remoteId);
            em.persist(product);
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product WHERE id = ?", Integer.class, remoteId.toString()))
                .isOne();
    }

    @Test
    void everyChangeIsAuditedWithActorTerminalAndPriorState() {
        User cashier = tx.execute(s -> persist(newCashier()));
        AuditActor actor = new AuditActor(cashier.getId(), cashier.getUsername());
        UUID productId = AuditContext.callAs(actor, () -> tx.execute(s -> persist(newProduct(10_000)).getId()));
        AuditContext.runAs(actor, () -> tx.executeWithoutResult(s -> em.find(Product.class, productId).reprice(9_000, 10_000)));

        tx.executeWithoutResult(s -> {
            AuditReader reader = AuditReaderFactory.get(em);
            List<Number> revisions = reader.getRevisions(Product.class, productId);
            assertThat(revisions).hasSize(2);
            assertThat(reader.find(Product.class, productId, revisions.get(0)).getSellingPricePaise()).isEqualTo(10_000);
            assertThat(reader.find(Product.class, productId, revisions.get(1)).getSellingPricePaise()).isEqualTo(9_000);

            AuditRevision revision = reader.findRevision(AuditRevision.class, revisions.get(1));
            assertThat(revision.getActorUserId()).isEqualTo(cashier.getId());
            assertThat(revision.getActorUsername()).isEqualTo(cashier.getUsername());
            assertThat(revision.getTerminalCode()).isEqualTo("T1");
            assertThat(revision.getRevisionUuid().version()).isEqualTo(7);
        });

        // Field-level edit log: the update revision flags exactly the columns it changed.
        assertThat(jdbc.queryForObject(
                "SELECT selling_price_paise_mod || ',' || mrp_paise_mod || ',' || name_mod FROM product_aud"
                        + " WHERE id = ? AND revtype = 1", String.class, productId.toString()))
                .isEqualTo("1,0,0");
    }

    @Test
    void unboundWorkIsAuditedAsSystem() {
        UUID productId = tx.execute(s -> persist(newProduct(1_000)).getId());

        assertThat(jdbc.queryForObject(
                "SELECT r.actor_username FROM product_aud a JOIN audit_revision r ON r.id = a.rev WHERE a.id = ?",
                String.class, productId.toString())).isEqualTo("SYSTEM");
    }

    @Test
    void deleteRevisionsKeepTheLastKnownState() {
        UUID productId = tx.execute(s -> persist(newProduct(1_000)).getId());
        tx.executeWithoutResult(s -> em.remove(em.find(Product.class, productId)));

        assertThat(jdbc.queryForObject("SELECT name FROM product_aud WHERE id = ? AND revtype = 2",
                String.class, productId.toString())).startsWith("Test product");
    }

    @Test
    void auditTrailIsAppendOnly() {
        UUID productId = tx.execute(s -> persist(newProduct(1_000)).getId());

        assertThatThrownBy(() -> jdbc.update("UPDATE product_aud SET name = 'forged' WHERE id = ?", productId.toString()))
                .hasMessageContaining("product_aud: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM product_aud WHERE id = ?", productId.toString()))
                .hasMessageContaining("product_aud: DELETE is not allowed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_revision"))
                .hasMessageContaining("audit_revision: DELETE is not allowed");
    }

    @Test
    void saleIsNumberedFrozenAndDrivesStockThroughTheLedger() {
        UUID[] ids = tx.execute(s -> {
            User cashier = persist(newCashier());
            Product product = persist(newProduct(10_000));
            persist(new InventoryLedgerEvent(product, LedgerEventType.OPENING_STOCK, 10_000, null, "T1",
                    Instant.now(), cashier, "opening balance"));

            InvoiceSequence sequence = persist(new InvoiceSequence("T1", "2627"));
            Invoice invoice = persist(newInvoice(sequence.next(), cashier, product));
            persist(new InventoryLedgerEvent(product, LedgerEventType.SALE, -2_000, invoice.getId(), "T1",
                    Instant.now(), cashier, null));
            return new UUID[] {invoice.getId(), product.getId()};
        });
        String invoiceId = ids[0].toString();

        tx.executeWithoutResult(s -> {
            Invoice invoice = em.find(Invoice.class, ids[0]);
            assertThat(invoice.getInvoiceNumber()).isEqualTo("T1-2627-00001");
            assertThat(invoice.getGrandTotalPaise()).isEqualTo(20_000);
            assertThat(invoice.getItems()).singleElement()
                    .satisfies(item -> assertThat(item.getLineTotalPaise()).isEqualTo(20_000));
            assertThat(invoice.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        });
        assertThat(jdbc.queryForObject("SELECT invoice_date FROM invoice WHERE id = ?", String.class, invoiceId))
                .isEqualTo("2026-09-11");

        Long stock = tx.execute(s -> em.createQuery(
                        "select coalesce(sum(e.quantityDeltaMilli), 0) from InventoryLedgerEvent e where e.product.id = :p",
                        Long.class)
                .setParameter("p", ids[1])
                .getSingleResult());
        assertThat(stock).isEqualTo(8_000);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM invoice WHERE id = ?", invoiceId))
                .hasMessageContaining("invoice: DELETE is not allowed");
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice SET grand_total_paise = 1 WHERE id = ?", invoiceId))
                .hasMessageContaining("issued invoices are frozen");
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice_item SET quantity_milli = 1"))
                .hasMessageContaining("invoice_item: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.update("UPDATE inventory_ledger_event SET quantity_delta_milli = 0"))
                .hasMessageContaining("inventory_ledger_event: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice_sequence SET last_issued = 0"))
                .hasMessageContaining("numbers can only move forward");

        // Cancellation is the one permitted change, and it is final.
        tx.executeWithoutResult(s -> {
            Invoice invoice = em.find(Invoice.class, ids[0]);
            invoice.cancel(invoice.getCashier(), "Customer left without paying", Instant.now());
        });
        assertThat(jdbc.queryForObject("SELECT status FROM invoice WHERE id = ?", String.class, invoiceId))
                .isEqualTo(InvoiceStatus.CANCELLED.name());
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice SET cancel_reason = 'edited' WHERE id = ?", invoiceId))
                .hasMessageContaining("issued invoices are frozen");
    }

    @Test
    void concurrentWritersQueueOnTheWriteLockInsteadOfFailing() throws Exception {
        UUID sequenceId = tx.execute(s -> persist(new InvoiceSequence("T9", "2627")).getId());
        int threads = 4;
        int perThread = 25;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        tx.executeWithoutResult(s -> em.find(InvoiceSequence.class, sequenceId).next());
                    }
                }));
            }
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS); // rethrows SQLITE_BUSY / optimistic-lock failures
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT last_issued FROM invoice_sequence WHERE id = ?", Long.class,
                sequenceId.toString())).isEqualTo((long) threads * perThread);
    }

    @Test
    void firstStartWritesAnEditableSettingsTemplate() throws java.io.IOException {
        String template = java.nio.file.Files.readString(java.nio.file.Path.of(DATA_DIR, "pos.properties"));

        assertThat(template).contains("#pos.company.gstin=", "#pos.company.legal-name=", "#pos.printer.type=");
        assertThat(template.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))).isEmpty();
    }

    @Test
    void issuedInvoiceKeepsTheSellerSnapshot() {
        UUID invoiceId = tx.execute(s -> {
            User cashier = persist(newCashier());
            Product product = persist(newProduct(10_000));
            InvoiceSequence sequence = persist(new InvoiceSequence("T3", "2627"));
            Invoice invoice = new Invoice("T3", sequence.next(), LocalDate.of(2026, 9, 11), Instant.now(),
                    TestFixtures.seller(), "27", null, null, cashier);
            invoice.addItem(new com.masspos.billing.InvoiceItem(product, 1_000, 10_000, 0, 8_475, 1_800, 0, 762, 763, 0, 0));
            return persist(invoice).getId();
        });

        assertThat(jdbc.queryForObject("SELECT seller_legal_name || '|' || seller_state_code || '|' || seller_address"
                + " || '|' || seller_cin FROM invoice WHERE id = ?", String.class, invoiceId.toString()))
                .isEqualTo("Mass Retail Private Limited|27|12 MG Road, Camp\nPune 411001|U52100MH2020PTC123456");
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice SET seller_legal_name = 'Someone else' WHERE id = ?",
                invoiceId.toString()))
                .hasMessageContaining("issued invoices are frozen");
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }
}
