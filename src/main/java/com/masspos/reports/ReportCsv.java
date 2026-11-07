package com.masspos.reports;

import com.masspos.common.Csv;
import com.masspos.common.IndiaTime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A report as a sheet, laid out section by section in the order the screen shows it, so what a
 * manager exports is what they were looking at.
 *
 * <p>Money is written as a plain decimal and never with a rupee sign or thousands separators:
 * this file is opened in Excel to be added up, and "Rs. 1,234.00" is text, not a number.
 */
public final class ReportCsv {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ReportCsv() {
    }

    public static String day(Reports.DayReport report, Instant exportedAt) {
        List<List<String>> rows = new ArrayList<>();
        heading(rows, "Day's takings", at(report.from()), at(report.to()), exportedAt);

        section(rows, "Summary");
        rows.add(List.of("Bills", String.valueOf(report.bills())));
        rows.add(List.of("Cancelled bills", String.valueOf(report.cancelledBills())));
        rows.add(List.of("Returns", String.valueOf(report.returns())));
        // The screen only splits sales from returns when there were returns; so does the sheet.
        if (report.returns() > 0) {
            rows.add(List.of("Sales", rupees(report.salesTotals().grandTotalPaise())));
            rows.add(List.of("Less returns", rupees(-report.returnTotals().grandTotalPaise())));
        }
        rows.add(List.of("Taxable value", rupees(report.totals().taxableValuePaise())));
        rows.add(List.of("CGST", rupees(report.totals().cgstPaise())));
        rows.add(List.of("SGST", rupees(report.totals().sgstPaise())));
        rows.add(List.of("IGST", rupees(report.totals().igstPaise())));
        rows.add(List.of("Cess", rupees(report.totals().cessPaise())));
        rows.add(List.of("Round off", rupees(report.totals().roundOffPaise())));
        rows.add(List.of("Grand total", rupees(report.totals().grandTotalPaise())));

        section(rows, "How it was paid");
        rows.add(List.of("Mode", "Bills", "Amount"));
        report.payments().forEach(row ->
                rows.add(List.of(row.mode().name(), String.valueOf(row.bills()), rupees(row.amountPaise()))));

        section(rows, "By cashier");
        rows.add(List.of("Cashier", "Bills", "Amount"));
        report.cashiers().forEach(row ->
                rows.add(List.of(row.name(), String.valueOf(row.bills()), rupees(row.amountPaise()))));

        // Every item that sold, not the ten the panel shows: a sheet is kept and read later, and
        // the one thing a shop cannot get back afterwards is what it sold that day.
        section(rows, "Items sold");
        rows.add(List.of("Item", "Quantity", "Bill lines", "Amount"));
        report.soldItems().forEach(row -> rows.add(List.of(row.name(), quantity(row.quantityMilli()),
                String.valueOf(row.lines()), rupees(row.amountPaise()))));

        section(rows, "By till");
        rows.add(List.of("Till", "Bills", "Amount"));
        report.terminals().forEach(row ->
                rows.add(List.of(row.name(), String.valueOf(row.bills()), rupees(row.amountPaise()))));

        return Csv.write(rows);
    }

    public static String gst(Reports.GstReport report, Instant exportedAt) {
        List<List<String>> rows = new ArrayList<>();
        heading(rows, "GST summary", report.from().toString(), report.to().toString(), exportedAt);

        section(rows, "Rate-wise summary");
        rows.add(List.of("Rate", "Taxable value", "CGST", "SGST", "IGST", "Cess"));
        report.rates().forEach(row -> rows.add(List.of(percent(row.gstRateBp()),
                rupees(row.taxableValuePaise()), rupees(row.cgstPaise()), rupees(row.sgstPaise()),
                rupees(row.igstPaise()), rupees(row.cessPaise()))));

        section(rows, "HSN summary");
        rows.add(List.of("HSN", "UQC", "Quantity", "Taxable value", "Tax"));
        report.hsn().forEach(row -> rows.add(List.of(
                row.hsnCode().isEmpty() ? "Not set" : row.hsnCode(),
                row.uqc().name(),
                quantity(row.quantityMilli()),
                rupees(row.taxableValuePaise()),
                rupees(row.cgstPaise() + row.sgstPaise() + row.igstPaise() + row.cessPaise()))));

        return Csv.write(rows);
    }

    public static String audit(List<Reports.AuditEntry> entries, String from, String to, Instant exportedAt) {
        List<List<String>> rows = new ArrayList<>();
        heading(rows, "Audit trail", from, to, exportedAt);

        section(rows, "Changes");
        rows.add(List.of("When", "Who", "Till", "What", "Change"));
        entries.forEach(entry -> rows.add(List.of(at(entry.at()), entry.user(), entry.terminal(),
                entry.entity().replace('_', ' '), entry.change())));

        return Csv.write(rows);
    }

    /** Every sheet says what it is and what window it covers, because the file outlives the screen. */
    private static void heading(List<List<String>> rows, String what, String from, String to, Instant exportedAt) {
        rows.add(List.of("Billing report", what));
        rows.add(List.of("From", from));
        rows.add(List.of("To", to));
        rows.add(List.of("Exported", at(exportedAt)));
    }

    private static void section(List<List<String>> rows, String title) {
        rows.add(List.of());
        rows.add(List.of(title));
    }

    private static String at(Instant moment) {
        return MOMENT.format(LocalDateTime.ofInstant(moment, IndiaTime.ZONE));
    }

    private static String rupees(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }

    private static String quantity(long milliUnits) {
        return BigDecimal.valueOf(milliUnits, 3).stripTrailingZeros().toPlainString();
    }

    private static String percent(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + "%";
    }
}
