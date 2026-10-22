package com.masspos.reports;

import com.masspos.auth.RequiresRole;
import com.masspos.common.IndiaTime;
import com.masspos.user.UserRole;
import com.masspos.web.FieldRejectedException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
@RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
public class ReportController {

    private static final int MAX_AUDIT_ROWS = 500;

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
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
}
