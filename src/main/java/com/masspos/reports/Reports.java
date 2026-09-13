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

        public Totals minus(Totals other) {
            return new Totals(taxableValuePaise - other.taxableValuePaise, cgstPaise - other.cgstPaise,
                    sgstPaise - other.sgstPaise, igstPaise - other.igstPaise, cessPaise - other.cessPaise,
                    roundOffPaise - other.roundOffPaise, grandTotalPaise - other.grandTotalPaise);
        }
    }

    /**
     * The day's takings: what a manager reads at closing, and what the drawer is counted against.
     * Returns are netted off everywhere money is shown.
     *
     * @param salesTotals  the bills alone
     * @param returnTotals the credit notes alone
     * @param totals       sales less returns: what the shop actually kept
     * @param payments     taken in less paid back, per mode
     */
    public record DayReport(LocalDate date, long bills, long cancelledBills, long returns, Totals salesTotals,
                            Totals returnTotals, Totals totals, List<ModeRow> payments, List<NameRow> cashiers,
                            List<NameRow> terminals, List<ProductRow> bestSellers) {
    }

    public record ModeRow(PaymentMode mode, long bills, long amountPaise) {
    }

    public record NameRow(String name, long bills, long amountPaise) {
    }

    public record ProductRow(String name, long quantityMilli, long amountPaise, long lines) {
    }

    /**
     * Rate-wise and HSN-wise figures, which is what filling in GSTR-1 needs. Every figure is net of the
     * credit notes issued in the period.
     */
    public record GstReport(LocalDate from, LocalDate to, Totals totals, Totals returnTotals, List<RateRow> rates,
                            List<HsnRow> hsn) {
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
