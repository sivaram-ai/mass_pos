package com.masspos.billing;

/**
 * What one invoice line costs and how the tax on it splits.
 *
 * @param grossPaise    quantity times price, before discount
 * @param discountPaise discount taken off before tax
 * @param lineTotalPaise what the customer pays for this line: taxable value plus every tax on it
 */
public record GstAmounts(long grossPaise, long discountPaise, long taxableValuePaise, long cgstPaise, long sgstPaise,
                         long igstPaise, long cessPaise, long lineTotalPaise) {

    public GstAmounts(long grossPaise, long discountPaise, long taxableValuePaise, long cgstPaise, long sgstPaise,
                      long igstPaise, long cessPaise) {
        this(grossPaise, discountPaise, taxableValuePaise, cgstPaise, sgstPaise, igstPaise, cessPaise,
                taxableValuePaise + cgstPaise + sgstPaise + igstPaise + cessPaise);
    }

    public long totalTaxPaise() {
        return cgstPaise + sgstPaise + igstPaise + cessPaise;
    }
}
