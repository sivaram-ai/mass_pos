package com.masspos.reports;

import com.masspos.auth.RequiresRole;
import com.masspos.common.IndiaTime;
import com.masspos.user.UserRole;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
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

    /** Closing report for one day: takings, tender split, who billed, and what sold. */
    @GetMapping("/day")
    public Reports.DayReport day(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reports.day(date == null ? LocalDate.now(IndiaTime.ZONE) : date);
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
