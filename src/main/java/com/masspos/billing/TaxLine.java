package com.masspos.billing;

import com.masspos.catalog.Product;
import com.masspos.catalog.UnitOfMeasure;

/** One line of a {@link TaxDocument}, with the amounts exactly as issued. */
public interface TaxLine {

    int getLineNo();

    Product getProduct();

    String getSku();

    String getProductName();

    /** Empty when the item has no HSN or SAC code. */
    String getHsnCode();

    UnitOfMeasure getUnit();

    long getQuantityMilli();

    long getUnitPricePaise();

    long getDiscountPaise();

    int getGstRateBp();

    long getTaxableValuePaise();

    long getCgstPaise();

    long getSgstPaise();

    long getIgstPaise();

    long getCessPaise();

    long getLineTotalPaise();
}
