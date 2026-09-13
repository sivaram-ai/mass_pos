package com.masspos.reports;

import com.masspos.billing.InvoiceItemRepository;
import com.masspos.billing.InvoicePaymentRepository;
import com.masspos.billing.InvoiceRepository;
import com.masspos.billing.InvoiceStatus;
import com.masspos.billing.PaymentMode;
import com.masspos.catalog.UnitOfMeasure;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static java.util.stream.Collectors.joining;

@Service
public class ReportService {

    private static final int BEST_SELLERS = 10;

    private final InvoiceRepository invoices;
    private final InvoiceItemRepository items;
    private final InvoicePaymentRepository payments;
    private final JdbcTemplate jdbc;

    public ReportService(InvoiceRepository invoices, InvoiceItemRepository items, InvoicePaymentRepository payments,
                         JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.items = items;
        this.payments = payments;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Reports.DayReport day(LocalDate date) {
        return new Reports.DayReport(date,
                invoices.countByInvoiceDateBetweenAndStatus(date, date, InvoiceStatus.ISSUED),
                invoices.countByInvoiceDateBetweenAndStatus(date, date, InvoiceStatus.CANCELLED),
                totals(date, date),
                payments.byMode(date, date, InvoiceStatus.ISSUED).stream()
                        .map(row -> new Reports.ModeRow((PaymentMode) row[0], count(row[1]), amount(row[2])))
                        .toList(),
                invoices.takingsByCashier(date, date, InvoiceStatus.ISSUED).stream()
                        .map(row -> new Reports.NameRow((String) row[0], count(row[1]), amount(row[2])))
                        .toList(),
                invoices.takingsByTerminal(date, date, InvoiceStatus.ISSUED).stream()
                        .map(row -> new Reports.NameRow((String) row[0], count(row[1]), amount(row[2])))
                        .toList(),
                items.bestSellers(date, date, InvoiceStatus.ISSUED, Limit.of(BEST_SELLERS)).stream()
                        .map(row -> new Reports.ProductRow((String) row[0], amount(row[1]), amount(row[2]),
                                count(row[3])))
                        .toList());
    }

    @Transactional(readOnly = true)
    public Reports.GstReport gst(LocalDate from, LocalDate to) {
        return new Reports.GstReport(from, to, totals(from, to),
                items.taxByRate(from, to, InvoiceStatus.ISSUED).stream()
                        .map(row -> new Reports.RateRow((Integer) row[0], amount(row[1]), amount(row[2]),
                                amount(row[3]), amount(row[4]), amount(row[5]), count(row[6])))
                        .toList(),
                items.taxByHsn(from, to, InvoiceStatus.ISSUED).stream()
                        .map(row -> new Reports.HsnRow((String) row[0], (UnitOfMeasure) row[1], amount(row[2]),
                                amount(row[3]), amount(row[4]), amount(row[5]), amount(row[6]), amount(row[7])))
                        .toList());
    }

    /**
     * The edit log, newest first: every audited change with who made it, on which till and when.
     * Read straight from the Envers tables, which are append-only in SQLite itself.
     */
    @Transactional(readOnly = true)
    public List<Reports.AuditEntry> audit(Instant from, Instant to, int limit) {
        List<String> auditTables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '%\\_aud' ESCAPE '\\' ORDER BY name",
                String.class);
        if (auditTables.isEmpty()) {
            return List.of();
        }
        // Table names come from sqlite_master, never from user input.
        String union = auditTables.stream()
                .map(table -> "SELECT rev, revtype, id, '%s' AS entity FROM %s"
                        .formatted(table.substring(0, table.length() - "_aud".length()), table))
                .collect(joining(" UNION ALL "));
        String sql = """
                SELECT r.timestamp, r.actor_username, r.terminal_code, c.entity, c.revtype, c.id
                FROM (%s) c JOIN audit_revision r ON r.id = c.rev
                WHERE r.timestamp BETWEEN ? AND ?
                ORDER BY r.timestamp DESC, c.entity ASC
                LIMIT ?
                """.formatted(union);
        return jdbc.query(sql, (rs, rowNum) -> new Reports.AuditEntry(
                Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                changeName(rs.getInt(5)), rs.getString(6)), from.toEpochMilli(), to.toEpochMilli(), limit);
    }

    private Reports.Totals totals(LocalDate from, LocalDate to) {
        List<Object[]> rows = invoices.totals(from, to, InvoiceStatus.ISSUED);
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return Reports.Totals.NONE;
        }
        Object[] row = rows.get(0);
        return new Reports.Totals(amount(row[0]), amount(row[1]), amount(row[2]), amount(row[3]), amount(row[4]),
                amount(row[5]), amount(row[6]));
    }

    private static String changeName(int revType) {
        return switch (revType) {
            case 0 -> "Created";
            case 1 -> "Changed";
            case 2 -> "Deleted";
            default -> "Unknown";
        };
    }

    private static long amount(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static long count(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
