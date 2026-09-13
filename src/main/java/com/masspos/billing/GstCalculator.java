package com.masspos.billing;

/**
 * Turns a shelf price and a quantity into the tax split that goes on the invoice. Integer paise
 * throughout: money never touches floating point.
 *
 * <p>Two rules decide the rounding, and they are what a customer and an auditor each expect:
 * <ul>
 *   <li>On a tax-inclusive price (the Indian retail default) the customer pays exactly the shelf
 *       price, so GST absorbs the rounding remainder rather than the total drifting by a paisa.</li>
 *   <li>CGST and SGST are always halves of one GST amount, with the odd paisa going to SGST, so
 *       the two halves re-sum to the tax charged. They can differ by one paisa: 30.51 splits
 *       15.25 + 15.26.</li>
 * </ul>
 */
public final class GstCalculator {

    private static final int PERCENT_BP = 10_000;
    private static final long PAISE_PER_RUPEE = 100;

    private GstCalculator() {
    }

    /**
     * @param quantityMilli  milli-units: 2 pieces is 2000, 1.25 kg is 1250
     * @param discountPaise  line discount, applied before tax
     * @param interState     place of supply differs from the seller's state, so IGST replaces CGST + SGST
     */
    public static GstAmounts line(long unitPricePaise, long quantityMilli, long discountPaise, int gstRateBp,
                                  int cessRateBp, boolean taxInclusive, boolean interState) {
        if (unitPricePaise < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        if (quantityMilli <= 0) {
            throw new IllegalArgumentException("Quantity must be positive; returns are credit notes");
        }
        if (gstRateBp < 0 || gstRateBp > PERCENT_BP || cessRateBp < 0 || cessRateBp > PERCENT_BP) {
            throw new IllegalArgumentException("Tax rates must be between 0 and 100%");
        }
        long gross = grossOf(unitPricePaise, quantityMilli);
        if (discountPaise < 0 || discountPaise > gross) {
            throw new IllegalArgumentException("Discount must be between 0 and the line value " + gross);
        }
        long net = gross - discountPaise;

        long taxable;
        long cess;
        long gstTax;
        if (taxInclusive) {
            taxable = roundedDiv(net * PERCENT_BP, PERCENT_BP + gstRateBp + cessRateBp);
            cess = roundedDiv(taxable * cessRateBp, PERCENT_BP);
            // Whatever rounding left over is GST, so the line total equals the marked price exactly.
            gstTax = net - taxable - cess;
        } else {
            taxable = net;
            cess = roundedDiv(taxable * cessRateBp, PERCENT_BP);
            gstTax = roundedDiv(taxable * gstRateBp, PERCENT_BP);
        }

        long cgst = 0;
        long sgst = 0;
        long igst = 0;
        if (interState) {
            igst = gstTax;
        } else {
            cgst = gstTax / 2;
            sgst = gstTax - cgst;
        }
        return new GstAmounts(gross, discountPaise, taxable, cgst, sgst, igst, cess);
    }

    /** Line value before discount and before any tax split. */
    public static long grossOf(long unitPricePaise, long quantityMilli) {
        return roundedDiv(unitPricePaise * quantityMilli, 1000);
    }

    /**
     * Signed adjustment that brings a bill to whole rupees, as section 170 of the CGST Act requires.
     * 1234.56 rounds down by 56 paise, 1234.60 up by 40.
     */
    public static long roundOffToRupee(long totalPaise) {
        long remainder = Math.floorMod(totalPaise, PAISE_PER_RUPEE);
        return remainder < PAISE_PER_RUPEE / 2 ? -remainder : PAISE_PER_RUPEE - remainder;
    }

    /** Half-up division for non-negative values. */
    private static long roundedDiv(long numerator, long denominator) {
        return (numerator + denominator / 2) / denominator;
    }
}
