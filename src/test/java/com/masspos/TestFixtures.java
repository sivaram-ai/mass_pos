package com.masspos;

import com.masspos.billing.Invoice;
import com.masspos.billing.InvoiceItem;
import com.masspos.billing.SellerDetails;
import com.masspos.catalog.Product;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.user.User;
import com.masspos.user.UserRole;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TestFixtures {

    /** A GSTIN with a correct check character (Maharashtra). */
    public static final String SELLER_GSTIN = "27AAPFU0939F1ZV";
    /** A GSTIN with a correct check character (Karnataka). */
    public static final String BUYER_GSTIN = "29AABCU9603R1ZJ";

    private TestFixtures() {
    }

    public static SellerDetails seller() {
        return new SellerDetails("Mass Retail Private Limited", "MASS MART", SELLER_GSTIN,
                List.of("12 MG Road, Camp", "Pune 411001"), "U52100MH2020PTC123456", "11521999000123");
    }

    public static User newCashier() {
        return new User("cashier-" + unique(), "Test Cashier", "$2a$10$placeholder.hash.for.tests.only", UserRole.CASHIER);
    }

    public static Product newProduct(long pricePaise) {
        return new Product("SKU-" + unique(), "Test product " + unique(), "3004", UnitOfMeasure.NOS,
                pricePaise, pricePaise, true, 1_800);
    }

    /** Intra-state B2C sale on 2026-09-11 10:54 IST: 2 x Rs 100.00 tax-inclusive at 18%. */
    public static Invoice newInvoice(long sequenceNo, User cashier, Product product) {
        Invoice invoice = new Invoice("T1", sequenceNo, LocalDate.of(2026, 9, 11), Instant.parse("2026-09-11T05:24:00Z"),
                seller(), "27", null, null, cashier);
        // taxable 169.49 + CGST 15.25 + SGST 15.26 = 200.00
        invoice.addItem(new InvoiceItem(product, 2_000, 10_000, 0, 16_949, 1_800, 0, 1_525, 1_526, 0, 0));
        return invoice;
    }

    public static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
