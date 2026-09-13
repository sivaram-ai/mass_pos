package com.masspos.audit;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static java.util.stream.Collectors.joining;

/**
 * Makes the audit trail and the books-of-account tables append-only inside SQLite itself, so that
 * neither an application bug nor a hand edit in a SQLite browser can rewrite history.
 *
 * <p>Runs once per start, after Hibernate has created or updated the schema (hence the
 * {@link EntityManagerFactory} dependency) and before the web server accepts requests. Triggers
 * are dropped and recreated in one transaction, so new {@code *_aud} tables and changed trigger
 * definitions are picked up and a table rebuild by a migration cannot leave a table unguarded.
 *
 * <p>This is a guard, not tamper-proofing: whoever owns the file can drop the triggers. Tamper
 * evidence comes from replicating revisions to the cloud (sync phase).
 */
@Component
public class AuditTrailGuard {

    private static final String AUDIT_REVISION_TABLE = "audit_revision";

    /** Invoice columns fixed at issue. Mirrors the {@code updatable = false} fields on Invoice. */
    private static final List<String> FROZEN_INVOICE_COLUMNS = List.of(
            "id", "invoice_number", "terminal_code", "financial_year", "sequence_no", "invoice_date",
            "issued_at", "seller_legal_name", "seller_trade_name", "seller_gstin", "seller_state_code",
            "seller_address", "seller_cin", "seller_fssai_license", "buyer_gstin", "buyer_name",
            "replaces_invoice_number", "place_of_supply", "taxable_value_paise", "cgst_paise", "sgst_paise", "igst_paise",
            "cess_paise", "round_off_paise", "grand_total_paise", "cashier_id", "created_at");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AuditTrailGuard(JdbcTemplate jdbc, TransactionTemplate tx, EntityManagerFactory schemaReady) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @PostConstruct
    void installTriggers() {
        tx.executeWithoutResult(status -> {
            List<String> auditTables = jdbc.queryForList(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND (name = ? OR name LIKE '%\\_aud' ESCAPE '\\')",
                    String.class, AUDIT_REVISION_TABLE);
            if (!auditTables.contains(AUDIT_REVISION_TABLE)) {
                throw new IllegalStateException(
                        "Audit trail tables are missing. Envers must never be disabled (MCA Rule 11(g)).");
            }
            for (String table : auditTables) {
                forbid(table, "UPDATE");
                forbid(table, "DELETE");
            }

            // Stock is the sum of ledger events; corrections are new compensating events.
            forbid("inventory_ledger_event", "UPDATE");
            forbid("inventory_ledger_event", "DELETE");

            // GST: an issued invoice is cancelled, never deleted or edited.
            forbid("invoice", "DELETE");
            forbid("invoice_item", "UPDATE");
            forbid("invoice_item", "DELETE");
            // Tenders are part of the books: the cash drawer is counted against them at closing.
            forbid("invoice_payment", "UPDATE");
            forbid("invoice_payment", "DELETE");
            String fiscalFieldChanged = FROZEN_INVOICE_COLUMNS.stream()
                    .map(column -> "NEW.%1$s IS NOT OLD.%1$s".formatted(column))
                    .collect(joining(" OR "));
            createTrigger("trg_invoice_frozen",
                    "BEFORE UPDATE ON invoice WHEN OLD.status = 'CANCELLED' OR " + fiscalFieldChanged,
                    "invoice: issued invoices are frozen, only cancellation is allowed");

            // A credit note is never edited or removed, nor are its lines or refunds: a mistaken
            // return is put right with a new bill.
            for (String table : List.of("credit_note", "credit_note_item", "credit_note_refund")) {
                forbid(table, "UPDATE");
                forbid(table, "DELETE");
            }

            // Gapless numbering: a sequence may only move forward.
            for (String sequence : List.of("invoice_sequence", "credit_note_sequence")) {
                forbid(sequence, "DELETE");
                createTrigger("trg_%s_forward_only".formatted(sequence),
                        "BEFORE UPDATE ON %s WHEN NEW.last_issued < OLD.last_issued".formatted(sequence)
                                + " OR NEW.terminal_code IS NOT OLD.terminal_code"
                                + " OR NEW.financial_year IS NOT OLD.financial_year",
                        "%s: numbers can only move forward".formatted(sequence));
            }
        });
    }

    private void forbid(String table, String operation) {
        if (!table.matches("[a-z0-9_]+")) {
            throw new IllegalStateException("Unexpected table name: " + table);
        }
        createTrigger("trg_%s_no_%s".formatted(table, operation.toLowerCase()),
                "BEFORE %s ON %s".formatted(operation, table),
                "%s: %s is not allowed".formatted(table, operation));
    }

    private void createTrigger(String name, String timingAndCondition, String message) {
        jdbc.execute("DROP TRIGGER IF EXISTS " + name);
        jdbc.execute("CREATE TRIGGER %s %s BEGIN SELECT RAISE(ABORT, '%s'); END"
                .formatted(name, timingAndCondition, message));
    }
}
