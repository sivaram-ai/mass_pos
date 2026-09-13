package com.masspos.billing;

import com.masspos.user.User;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What a bill and a credit note have in common: a numbered document from a seller, with lines and
 * GST totals. The receipt printer lays both out from this.
 */
public interface TaxDocument {

    String getNumber();

    LocalDate getDocumentDate();

    Instant getIssuedAt();

    SellerDetails getSeller();

    User getCashier();

    /** Null for a walk-in customer. */
    String getBuyerGstin();

    String getBuyerName();

    /** State code; empty for a shop without a GSTIN. */
    String getPlaceOfSupply();

    List<? extends TaxLine> getLines();

    long getTaxableValuePaise();

    long getCgstPaise();

    long getSgstPaise();

    long getIgstPaise();

    long getCessPaise();

    long getRoundOffPaise();

    long getGrandTotalPaise();

    /** False for a shop without a GSTIN: no tax was charged, so it is not a GST document. */
    default boolean isTaxInvoice() {
        return getSeller().isGstRegistered();
    }

    default boolean isInterState() {
        return isTaxInvoice() && !getSeller().getStateCode().equals(getPlaceOfSupply());
    }

    default boolean isB2b() {
        return getBuyerGstin() != null;
    }
}
