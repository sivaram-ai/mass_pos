package com.masspos.reports;

import com.masspos.billing.CreditNoteItemRepository;
import com.masspos.billing.CreditNoteRefundRepository;
import com.masspos.billing.CreditNoteRepository;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.util.stream.Collectors.joining;

@Service
public class ReportService {

    private static final int BEST_SELLERS = 10;

    private final InvoiceRepository invoices;
    private final InvoiceItemRepository items;
    private final InvoicePaymentRepository payments;
    private final CreditNoteRepository creditNotes;
    private final CreditNoteItemRepository creditNoteItems;
    private final CreditNoteRefundRepository refunds;
    private final JdbcTemplate jdbc;

    public ReportService(InvoiceRepository invoices, InvoiceItemRepository items, InvoicePaymentRepository payments,
                         CreditNoteRepository creditNotes, CreditNoteItemRepository creditNoteItems,
                         CreditNoteRefundRepository refunds, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.items = items;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.creditNoteItems = creditNoteItems;
        this.refunds = refunds;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Reports.DayReport day(LocalDate date) {
        Reports.Totals sales = totalsOf(invoices.totals(date, date, InvoiceStatus.ISSUED));
        Reports.Totals returned = totalsOf(creditNotes.totals(date, date));

        // Money in per mode, less money paid back in that mode. A mode used only for refunds still shows.
        Map<PaymentMode, long[]> modes = new TreeMap<>();
        payments.byMode(date, date, InvoiceStatus.ISSUED).forEach(row ->
                add(modes, (PaymentMode) row[0], count(row[1]), amount(row[2])));
        refunds.byMode(date, date).forEach(row -> add(modes, (PaymentMode) row[0], 0, -amount(row[2])));

        return new Reports.DayReport(date,
                invoices.countByInvoiceDateBetweenAndStatus(date, date, InvoiceStatus.ISSUED),
                invoices.countByInvoiceDateBetweenAndStatus(date, date, InvoiceStatus.CANCELLED),
                creditNotes.countByNoteDateBetween(date, date),
                sales, returned, sales.minus(returned),
                modes.entrySet().stream()
                        .map(mode -> new Reports.ModeRow(mode.getKey(), mode.getValue()[0], mode.getValue()[1]))
                        .toList(),
                net(invoices.takingsByCashier(date, date, InvoiceStatus.ISSUED), creditNotes.refundsByCashier(date, date)),
                net(invoices.takingsByTerminal(date, date, InvoiceStatus.ISSUED), creditNotes.refundsByTerminal(date, date)),
                items.bestSellers(date, date, InvoiceStatus.ISSUED, Limit.of(BEST_SELLERS)).stream()
                        .map(row -> new Reports.ProductRow((String) row[0], amount(row[1]), amount(row[2]),
                                count(row[3])))
                        .toList());
    }

    @Transactional(readOnly = true)
    public Reports.GstReport gst(LocalDate from, LocalDate to) {
        Reports.Totals returned = totalsOf(creditNotes.totals(from, to));

        // rate -> {taxable, cgst, sgst, igst, cess}, then the count of bill lines (returns never lower it)
        Map<Integer, long[]> rates = new TreeMap<>();
        items.taxByRate(from, to, InvoiceStatus.ISSUED).forEach(row -> {
            long[] sums = addUp(rates, (Integer) row[0], row, 1, 5, 1);
            sums[5] += count(row[6]);
        });
        creditNoteItems.taxByRate(from, to).forEach(row -> addUp(rates, (Integer) row[0], row, 1, 5, -1));

        // hsn + unit -> {quantity, taxable, cgst, sgst, igst, cess}
        Map<List<Object>, long[]> hsn = new TreeMap<>(Comparator
                .comparing((List<Object> key) -> (String) key.get(0))
                .thenComparing(key -> ((UnitOfMeasure) key.get(1)).name()));
        items.taxByHsn(from, to, InvoiceStatus.ISSUED).forEach(row -> addUp(hsn, List.of(row[0], row[1]), row, 2, 6, 1));
        creditNoteItems.taxByHsn(from, to).forEach(row -> addUp(hsn, List.of(row[0], row[1]), row, 2, 6, -1));

        return new Reports.GstReport(from, to, totalsOf(invoices.totals(from, to, InvoiceStatus.ISSUED)).minus(returned),
                returned,
                rates.entrySet().stream()
                        .map(rate -> {
                            long[] v = rate.getValue();
                            return new Reports.RateRow(rate.getKey(), v[0], v[1], v[2], v[3], v[4], v[5]);
                        })
                        .toList(),
                hsn.entrySet().stream()
                        .map(code -> {
                            long[] v = code.getValue();
                            return new Reports.HsnRow((String) code.getKey().get(0), (UnitOfMeasure) code.getKey().get(1),
                                    v[0], v[1], v[2], v[3], v[4], v[5]);
                        })
                        .toList());
    }

    /**
     * Adds {@code width} figures of a query row, starting at column {@code first}, into the sums kept
     * for its key: sales with sign 1, returns with sign -1.
     */
    private static <K> long[] addUp(Map<K, long[]> sums, K key, Object[] row, int first, int width, int sign) {
        long[] into = sums.computeIfAbsent(key, k -> new long[6]);
        for (int i = 0; i < width; i++) {
            into[i] += sign * amount(row[first + i]);
        }
        return into;
    }

    private static void add(Map<PaymentMode, long[]> modes, PaymentMode mode, long bills, long amount) {
        long[] sums = modes.computeIfAbsent(mode, m -> new long[2]);
        sums[0] += bills;
        sums[1] += amount;
    }

    /** Takings by cashier or till less their refunds, largest first. */
    private static List<Reports.NameRow> net(List<Object[]> taken, List<Object[]> paidBack) {
        Map<String, long[]> byName = new LinkedHashMap<>();
        taken.forEach(row -> {
            long[] sums = byName.computeIfAbsent((String) row[0], name -> new long[2]);
            sums[0] += count(row[1]);
            sums[1] += amount(row[2]);
        });
        paidBack.forEach(row -> byName.computeIfAbsent((String) row[0], name -> new long[2])[1] -= amount(row[2]));
        return byName.entrySet().stream()
                .map(entry -> new Reports.NameRow(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingLong(Reports.NameRow::amountPaise).reversed())
                .toList();
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

    private static Reports.Totals totalsOf(List<Object[]> rows) {
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
