package com.masspos.reports;

import com.masspos.auth.RequiresRole;
import com.masspos.common.Csv;
import com.masspos.common.IndiaTime;
import com.masspos.hardware.ThermalPrinter;
import com.masspos.settings.ShopSettingsService;
import com.masspos.user.UserRole;
import com.masspos.web.FieldRejectedException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/reports")
@RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
public class ReportController {

    private static final int MAX_AUDIT_ROWS = 500;
    private static final DateTimeFormatter FILE_MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    private final ReportService reports;
    private final ThermalPrinter printer;
    private final ShopSettingsService shop;

    public ReportController(ReportService reports, ThermalPrinter printer, ShopSettingsService shop) {
        this.reports = reports;
        this.printer = printer;
        this.shop = shop;
    }

    /**
     * Closing report: takings, tender split, who billed, and what sold. One day by default, or any
     * stretch of time - a week, a month, or this evening's shift - with from and to, which carry a
     * time of day and are matched against when each bill was issued.
     *
     * @param date a whole single day, for anything that still asks that way; from and to win
     */
    @GetMapping("/day")
    public Reports.DayReport day(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        LocalDate oneDay = date == null ? LocalDate.now(IndiaTime.ZONE) : date;
        LocalDateTime start = from == null ? oneDay.atStartOfDay() : from;
        // No time given for the end means the whole of that day, to midnight; a shop asking for
        // "the 22nd" wants the 22nd, not a window of zero length.
        LocalDateTime end = to != null ? to : start.toLocalDate().atTime(LocalTime.MAX);
        if (end.isBefore(start)) {
            throw new FieldRejectedException("to", "is before the moment the range starts");
        }
        return reports.day(start.atZone(IndiaTime.ZONE).toInstant(), end.atZone(IndiaTime.ZONE).toInstant());
    }

    /** Rate-wise and HSN-wise figures for a GSTR-1 filing period. */
    @GetMapping("/gst")
    public Reports.GstReport gst(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        LocalDate start = from == null ? today.withDayOfMonth(1) : from;
        return reports.gst(start, to == null ? today : to);
    }

    /** The MCA Rule 11(g) edit log, newest first. */
    @GetMapping("/audit")
    public List<Reports.AuditEntry> audit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "200") int limit) {
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? today : to;
        Instant fromInstant = start.atStartOfDay(IndiaTime.ZONE).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(IndiaTime.ZONE).toInstant();
        return reports.audit(fromInstant, toInstant, Math.min(limit, MAX_AUDIT_ROWS));
    }

    /**
     * Whatever is on the reports screen, as a sheet. The window and the row limit are the ones the
     * screen asked with, and each report is built by the same method that answers the screen, so a
     * sheet can never show different figures from the ones it was exported from.
     *
     * <p>{@code from} and {@code to} arrive as text because the day report counts in moments and
     * the other two in whole days, and one parameter cannot be bound as both.
     *
     * @param report day, gst or audit: the tab that was open
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam(defaultValue = "day") String report,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(defaultValue = "300") int limit) {
        Instant exportedAt = Instant.now();
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        String csv = switch (report) {
            case "day" -> ReportCsv.day(day(null, moment("from", from), moment("to", to)), exportedAt);
            case "gst" -> ReportCsv.gst(gst(day("from", from), day("to", to)), exportedAt);
            case "audit" -> {
                LocalDate start = from == null ? today : day("from", from);
                LocalDate end = to == null ? today : day("to", to);
                yield ReportCsv.audit(audit(start, end, limit), start.toString(), end.toString(), exportedAt);
            }
            default -> throw new FieldRejectedException("report", "is not a report this till can export");
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename(report, from, to, exportedAt) + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(Csv.EXCEL_BOM + csv);
    }

    /**
     * The day's takings on the till's own printer, for the manager closing up with the drawer in
     * front of them. {@code items} chooses how much of what sold goes on the paper: everything, or
     * the handful the screen calls the best sellers.
     *
     * <p>The report is built first and printed after, so SQLite's write lock is never held while
     * waiting on a printer.
     */
    @PostMapping("/print")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void print(@RequestParam(required = false) String from,
                      @RequestParam(required = false) String to,
                      @RequestParam(defaultValue = "ALL") ReportPrint.Items items) {
        Reports.DayReport report = day(null, moment("from", from), moment("to", to));
        printer.await(printer.print(ReportPrint.format(report, shop.currentOrEmpty(), printer.settings(), items)));
    }

    /**
     * {@code billing_reports_<window>_exported_<when>.csv}. Colons are not allowed in a Windows
     * file name, so every moment is written with dashes.
     */
    private static String filename(String report, String from, String to, Instant exportedAt) {
        String window = Stream.of(from, to).filter(part -> part != null && !part.isBlank())
                .map(part -> part.replace(':', '-'))
                .collect(Collectors.joining("_to_"));
        String when = FILE_MOMENT.format(LocalDateTime.ofInstant(exportedAt, IndiaTime.ZONE));
        return "billing_reports_" + report + (window.isEmpty() ? "" : "_" + window) + "_exported_" + when + ".csv";
    }

    private static LocalDateTime moment(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // A browser's datetime-local sends "2026-11-07T22:14"; a plain date means its first moment.
            return value.length() <= 10 ? LocalDate.parse(value).atStartOfDay() : LocalDateTime.parse(value);
        } catch (DateTimeParseException notAMoment) {
            throw new FieldRejectedException(field, "is not a date and time this till can read");
        }
    }

    private static LocalDate day(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException notADate) {
            throw new FieldRejectedException(field, "is not a date this till can read");
        }
    }
}
