package com.masspos.reports;

import com.masspos.billing.PaymentMode;
import com.masspos.catalog.UnitOfMeasure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Shapes returned by the reports API. Money stays in paise; the screen formats it. */
public final class Reports {

    private Reports() {
    }

    public record Totals(long taxableValuePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise,
                         long roundOffPaise, long grandTotalPaise) {

        public static final Totals NONE = new Totals(0, 0, 0, 0, 0, 0, 0);
    }

    /** The day's takings: what a manager reads at closing, and what the drawer is counted against. */
    public record DayReport(LocalDate date, long bills, long cancelledBills, Totals totals,
                            List<ModeRow> payments, List<NameRow> cashiers, List<NameRow> terminals,
                            List<ProductRow> bestSellers) {
    }

    public record ModeRow(PaymentMode mode, long bills, long amountPaise) {
    }

    public record NameRow(String name, long bills, long amountPaise) {
    }

    public record ProductRow(String name, long quantityMilli, long amountPaise, long lines) {
    }

    /** Rate-wise and HSN-wise figures, which is what filling in GSTR-1 needs. */
    public record GstReport(LocalDate from, LocalDate to, Totals totals, List<RateRow> rates, List<HsnRow> hsn) {
    }

    public record RateRow(int gstRateBp, long taxableValuePaise, long cgstPaise, long sgstPaise, long igstPaise,
                          long cessPaise, long lines) {
    }

    public record HsnRow(String hsnCode, UnitOfMeasure uqc, long quantityMilli, long taxableValuePaise,
                         long cgstPaise, long sgstPaise, long igstPaise, long cessPaise) {
    }

    /**
     * One row of the MCA Rule 11(g) edit log.
     *
     * @param change Created, Changed or Deleted
     */
    public record AuditEntry(Instant at, String user, String terminal, String entity, String change, String entityId) {
    }
}
