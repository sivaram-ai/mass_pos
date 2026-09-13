package com.masspos.receipt;

import com.masspos.TestFixtures;
import com.masspos.billing.Invoice;
import com.masspos.billing.InvoiceItem;
import com.masspos.billing.SellerDetails;
import com.masspos.catalog.Product;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.hardware.CodePage;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.PrinterProperties;
import com.masspos.settings.ShopSettings;
import com.masspos.settings.ShopSettingsForm;
import com.masspos.user.User;
import com.masspos.user.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptFormatterTest {

    @Test
    void intraStateB2cReceiptCarriesTheRule46Fields() {
        String receipt = format(48, intraStateSale(), shop(), ReceiptCopy.ORIGINAL);

        assertThat(receipt)
                .contains("ORIGINAL FOR RECIPIENT", "MASS MART", "Mass Retail Private Limited", "12 MG Road, Camp",
                        "Ph: 020-5550100", "accounts@massmart.example", "GSTIN: 27AAPFU0939F1ZV",
                        "CIN: U52100MH2020PTC123456", "FSSAI Lic. No: 11521999000123", "TAX INVOICE",
                        "Invoice: T1-2627-00001", "Date: 11-09-2026", "Time: 10:54", "Paracetamol 500mg",
                        "HSN 30049099 18%  2 x 100.00", "CGST", "15.25", "SGST", "15.26", "TOTAL Rs.", "200.00",
                        "Place of supply: 27-Maharashtra", "Reverse charge: No",
                        "Goods once sold are exchanged within 7 days", "Thank you! Visit again")
                .doesNotContain("IGST", "Buyer GSTIN", "CANCELLED");
    }

    @Test
    void sellerIsPrintedAsIssuedNotAsCurrentlyConfigured() {
        ShopSettings renamed = new ShopSettings();
        renamed.apply(new ShopSettingsForm("Renamed Traders LLP", "RENAMED", "29AABCU9603R1ZJ",
                List.of("New Address"), "", "", "", "", "", "", List.of(), "", "", "", true, false));

        assertThat(format(48, intraStateSale(), renamed, ReceiptCopy.DUPLICATE))
                .contains("MASS MART", "12 MG Road, Camp", "GSTIN: 27AAPFU0939F1ZV")
                .doesNotContain("RENAMED", "Renamed Traders", "New Address", "29AABCU9603R1ZJ");
    }

    @Test
    void interStateB2bReceiptShowsIgstBuyerAndSignatory() {
        String receipt = format(48, interStateB2bSale(), shop(), ReceiptCopy.ORIGINAL);

        assertThat(receipt)
                .contains("IGST", "30.51", "Bill to: Karnataka Traders", "Buyer GSTIN: " + TestFixtures.BUYER_GSTIN,
                        "Place of supply: 29-Karnataka", "For Mass Retail Private Limited", "Authorised Signatory")
                .doesNotContain("CGST", "SGST");
    }

    @Test
    void aShopWithoutGstinPrintsAPlainBillWithNoTax() {
        SellerDetails unregistered = new SellerDetails("Mass Tiffin Centre", "NEW MASS", "",
                List.of("4 Bazaar Street", "Madurai"), "", "");
        Invoice invoice = new Invoice("T1", 7, LocalDate.of(2026, 9, 11), Instant.parse("2026-09-11T05:24:00Z"),
                unregistered, "", null, null, new User("asha", "Asha K", "hash", UserRole.CASHIER));
        Product parotta = new Product("1", "Parotta", "", UnitOfMeasure.NOS, 2_000, 0, true, 0);
        invoice.addItem(new InvoiceItem(parotta, 3_000, 2_000, 0, 6_000, 0, 0, 0, 0, 0, 0));

        String receipt = format(48, invoice, shop(), ReceiptCopy.ORIGINAL);

        assertThat(receipt)
                .contains("NEW MASS", "4 Bazaar Street", "BILL", "Bill No: T1-2627-00007", "Parotta",
                        "3 x 20.00", "60.00", "TOTAL Rs.")
                .doesNotContain("TAX INVOICE", "GSTIN", "HSN", "CGST", "SGST", "Taxable", "GST%",
                        "Place of supply", "Reverse charge", "CIN", "FSSAI");
        assertThat(invoice.isTaxInvoice()).isFalse();
        assertThat(invoice.isInterState()).isFalse();
    }

    @Test
    void everyPrintedLineFitsThePaper() {
        for (int columns : new int[] {32, 48}) {
            for (Invoice invoice : List.of(intraStateSale(), interStateB2bSale())) {
                assertThat(format(columns, invoice, shop(), ReceiptCopy.ORIGINAL).lines())
                        .filteredOn(line -> !line.contains("[QR "))
                        .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(columns));
            }
        }
    }

    @Test
    void reprintsAndCancellationsAreMarkedAndNeverAskForPayment() {
        Invoice sale = intraStateSale();
        sale.cancel(sale.getCashier(), "Wrong item scanned", Instant.parse("2026-09-11T05:30:00Z"));

        assertThat(format(48, sale, shop(), ReceiptCopy.DUPLICATE))
                .contains("DUPLICATE COPY", "*** CANCELLED ***", "Cancelled: Wrong item scanned")
                .doesNotContain("upi://");
    }

    @Test
    void originalReceiptCarriesAUpiQrForTheBillAmount() {
        assertThat(format(48, intraStateSale(), shop(), ReceiptCopy.ORIGINAL))
                .contains("upi://pay?pa=massmart@okicici&pn=MASS%20MART&am=200.00&cu=INR&tn=T1-2627-00001");
    }

    @Test
    void drawerKickComesBeforeAnythingIsPrinted() {
        byte[] bytes = new ReceiptFormatter(printer(48))
                .format(intraStateSale(), shop(), ReceiptCopy.ORIGINAL, true).toBytes();

        // after ESC @ and ESC t n
        assertThat(Arrays.copyOfRange(bytes, 5, 10)).containsExactly((byte) 0x1B, (byte) 'p', (byte) 0, (byte) 50, (byte) 250);
    }

    @Test
    void formatsStorageUnits() {
        assertThat(Amounts.rupees(20_000)).isEqualTo("200.00");
        assertThat(Amounts.rupees(-150)).isEqualTo("-1.50");
        assertThat(Amounts.rupees(5)).isEqualTo("0.05");
        assertThat(Amounts.quantity(1_250)).isEqualTo("1.25");
        assertThat(Amounts.quantity(10_000)).isEqualTo("10");
        assertThat(Amounts.percent(250)).isEqualTo("2.5%");
        assertThat(Amounts.percent(0)).isEqualTo("0%");
    }

    private static ShopSettings shop() {
        ShopSettings shop = new ShopSettings();
        shop.apply(new ShopSettingsForm("Mass Retail Private Limited", "MASS MART", TestFixtures.SELLER_GSTIN,
                List.of("12 MG Road, Camp", "Pune 411001"), "020-5550100", "accounts@massmart.example",
                "www.massmart.example", "U52100MH2020PTC123456", "11521999000123", "massmart@okicici",
                List.of("Goods once sold are exchanged within 7 days", "Thank you! Visit again"),
                "Ravi Systems", "98200-00000", "support@example.com", true, false));
        return shop;
    }

    private static Invoice intraStateSale() {
        Invoice invoice = invoice("27", null, null);
        invoice.addItem(new InvoiceItem(paracetamol(), 2_000, 10_000, 0, 16_949, 1_800, 0, 1_525, 1_526, 0, 0));
        return invoice;
    }

    private static Invoice interStateB2bSale() {
        Invoice invoice = invoice("29", TestFixtures.BUYER_GSTIN, "Karnataka Traders");
        invoice.addItem(new InvoiceItem(paracetamol(), 2_000, 10_000, 0, 16_949, 1_800, 0, 0, 0, 3_051, 0));
        return invoice;
    }

    private static Invoice invoice(String placeOfSupply, String buyerGstin, String buyerName) {
        User cashier = new User("asha", "Asha K", "hash", UserRole.CASHIER);
        return new Invoice("T1", 1, LocalDate.of(2026, 9, 11), Instant.parse("2026-09-11T05:24:00Z"),
                TestFixtures.seller(), placeOfSupply, buyerGstin, buyerName, cashier);
    }

    private static Product paracetamol() {
        return new Product("SKU-1", "Paracetamol 500mg strip of 15 tablets", "30049099", UnitOfMeasure.NOS,
                10_000, 10_000, true, 1_800);
    }

    private static PrinterProperties printer(int columns) {
        return new PrinterProperties(PrinterProperties.Type.FILE,
                new PrinterProperties.Serial("", 9600, PrinterProperties.FlowControl.NONE),
                new PrinterProperties.Tcp("", 9100), columns, CodePage.PC437, EscPos.DrawerPin.PIN_2,
                Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    private static String format(int columns, Invoice invoice, ShopSettings shop, ReceiptCopy copy) {
        return new ReceiptFormatter(printer(columns)).format(invoice, shop, copy, false).plainText();
    }
}
