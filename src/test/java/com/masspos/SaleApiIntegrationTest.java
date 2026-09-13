package com.masspos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.auth.AuthService;
import com.masspos.user.UserRole;
import com.masspos.web.LocalApiGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "pos.data-dir=" + SaleApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.gstin=27AAPFU0939F1ZV",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
class SaleApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/sales-it";
    private static final Path PRINTER_OUT = Path.of(DATA_DIR, "printer-out");

    private static String cashierToken;
    private static String managerToken;

    static {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AuthService auth;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void staffOnShift() {
        if (cashierToken == null) {
            cashierToken = TestAccounts.tokenFor(auth, "sale-cashier", UserRole.CASHIER);
            managerToken = TestAccounts.tokenFor(auth, "sale-manager", UserRole.MANAGER);
        }
    }

    @Test
    void sellsAnItemAndPrintsAGstBill() throws Exception {
        String productId = newProductWithStock(10_000);

        JsonNode sale = postJson("/api/sales", cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":2000,"discountPaise":0}],
                 "payments":[{"mode":"CASH","amountPaise":20000,"tenderedPaise":50000}],
                 "print":true,"openDrawer":true}
                """.formatted(productId), status().isCreated());

        JsonNode invoice = sale.get("invoice");
        assertThat(invoice.get("invoiceNumber").asText()).matches("T1-\\d{4}-\\d{5}");
        assertThat(invoice.get("taxableValuePaise").asLong()).isEqualTo(16_949);
        assertThat(invoice.get("cgstPaise").asLong()).isEqualTo(1_525);
        assertThat(invoice.get("sgstPaise").asLong()).isEqualTo(1_526);
        assertThat(invoice.get("grandTotalPaise").asLong()).isEqualTo(20_000);
        assertThat(invoice.get("changePaise").asLong()).isEqualTo(30_000);
        assertThat(sale.get("printed").asBoolean()).isTrue();

        assertThat(Files.readString(latestReceipt()))
                .contains(invoice.get("invoiceNumber").asText(), "MASS MART", "TOTAL Rs.", "200.00");
    }

    @Test
    void sellingTakesTheStockDownAndCancellingPutsItBack() throws Exception {
        String productId = newProductWithStock(10_000);

        JsonNode sale = postJson("/api/sales", cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":3000,"discountPaise":0}],
                 "payments":[{"mode":"UPI","amountPaise":30000,"tenderedPaise":30000,"reference":"UPI123"}],
                 "print":false}
                """.formatted(productId), status().isCreated());
        String invoiceId = sale.get("invoice").get("id").asText();

        assertThat(stockOf(productId)).isEqualTo(7_000);

        // A cashier may not undo a sale.
        mvc.perform(authed(post("/api/invoices/" + invoiceId + "/cancel"), cashierToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"Mistake\"}"))
                .andExpect(status().isForbidden());

        JsonNode cancelled = postJson("/api/invoices/" + invoiceId + "/cancel", managerToken,
                "{\"reason\":\"Customer changed their mind\"}", status().isOk());

        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(stockOf(productId)).isEqualTo(10_000);
    }

    @Test
    void invoiceNumbersRunInOrderWithoutGaps() throws Exception {
        String productId = newProductWithStock(10_000);

        long first = sequenceNumberOf(sellOne(productId));
        long second = sequenceNumberOf(sellOne(productId));

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void theTillRemembersItsLastBillForAReprint() throws Exception {
        String productId = newProductWithStock(10_000);
        String invoiceNumber = sellOne(productId);

        mvc.perform(authed(get("/api/invoices/last"), cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value(invoiceNumber))
                .andExpect(jsonPath("$.grandTotalPaise").value(10_000));
    }

    @Test
    void splitPaymentsMustAddUpToTheBill() throws Exception {
        String productId = newProductWithStock(10_000);

        mvc.perform(authed(post("/api/sales"), cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("""
                                {"lines":[{"productId":"%s","quantityMilli":2000,"discountPaise":0}],
                                 "payments":[{"mode":"CASH","amountPaise":5000,"tenderedPaise":5000}],
                                 "print":false}
                                """.formatted(productId)))
                .andExpect(status().isBadRequest());

        JsonNode split = postJson("/api/sales", cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":2000,"discountPaise":0}],
                 "payments":[{"mode":"CASH","amountPaise":5000,"tenderedPaise":5000},
                             {"mode":"CARD","amountPaise":15000,"tenderedPaise":15000,"reference":"APPR-9"}],
                 "print":false}
                """.formatted(productId), status().isCreated());

        assertThat(split.get("invoice").get("payments")).hasSize(2);
    }

    @Test
    void aBillCanBeParkedAndPickedUpAgain() throws Exception {
        String productId = newProductWithStock(10_000);

        JsonNode held = postJson("/api/holds", cashierToken, """
                {"label":"Kiran, blue shirt","estimatedTotalPaise":20000,
                 "cart":{"lines":[{"productId":"%s","quantityMilli":2000}]}}
                """.formatted(productId), status().isCreated());

        mvc.perform(authed(get("/api/holds"), cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label=='Kiran, blue shirt')]").exists());

        JsonNode resumed = json.readTree(mvc.perform(authed(delete("/api/holds/" + held.get("id").asText()),
                                cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(resumed.get("cart").get("lines").get(0).get("productId").asText()).isEqualTo(productId);
        // Parking a bill consumes no invoice number, and picking it up clears the hold.
        mvc.perform(authed(get("/api/holds"), cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label=='Kiran, blue shirt')]").doesNotExist());
    }

    @Test
    void heldBillsListTheLatestFirst() throws Exception {
        String productId = newProductWithStock(1_000);
        String cart = "{\"lines\":[{\"productId\":\"%s\",\"quantityMilli\":1000}]}".formatted(productId);
        postJson("/api/holds", cashierToken, "{\"label\":\"Older table\",\"estimatedTotalPaise\":100,\"cart\":%s}"
                .formatted(cart), status().isCreated());
        postJson("/api/holds", cashierToken, "{\"label\":\"Newer table\",\"estimatedTotalPaise\":100,\"cart\":%s}"
                .formatted(cart), status().isCreated());

        JsonNode holds = json.readTree(mvc.perform(authed(get("/api/holds"), cashierToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        java.util.List<String> labels = holds.findValuesAsText("label");
        assertThat(labels.indexOf("Newer table")).isLessThan(labels.indexOf("Older table"));
    }

    @Test
    void anInterStateBillChargesIgst() throws Exception {
        String productId = newProductWithStock(10_000);

        JsonNode sale = postJson("/api/sales", cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":2000,"discountPaise":0}],
                 "payments":[{"mode":"CARD","amountPaise":20000,"tenderedPaise":20000}],
                 "buyerGstin":"29AABCU9603R1ZJ","buyerName":"Karnataka Traders","placeOfSupply":"29",
                 "print":false}
                """.formatted(productId), status().isCreated());

        JsonNode invoice = sale.get("invoice");
        assertThat(invoice.get("igstPaise").asLong()).isEqualTo(3_051);
        assertThat(invoice.get("cgstPaise").asLong()).isZero();
    }

    @Test
    void aMistypedBuyerGstinIsRefused() throws Exception {
        String productId = newProductWithStock(10_000);

        mvc.perform(authed(post("/api/sales"), cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("""
                                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],
                                 "payments":[{"mode":"CASH","amountPaise":10000,"tenderedPaise":10000}],
                                 "buyerGstin":"29AABCU9603R1ZM","print":false}
                                """.formatted(productId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void findsAnItemByAnyPartOfItsCode() throws Exception {
        postJson("/api/products", managerToken, """
                {"sku":"PG-100","barcode":"8901719100017","name":"Parle-G Biscuits 100g","hsnCode":"19053100",
                 "unit":"NOS","sellingPricePaise":2000,"mrpPaise":2000,"taxInclusive":true,"gstRateBp":1800,
                 "cessRateBp":0}
                """, status().isCreated());

        // A cashier types the few characters they remember, in whatever case, not the whole code.
        assertThat(searchNames("pg")).contains("Parle-G Biscuits 100g");
        assertThat(searchNames("PG")).contains("Parle-G Biscuits 100g");
        assertThat(searchNames("g-100")).contains("Parle-G Biscuits 100g");
        assertThat(searchNames("biscuits")).contains("Parle-G Biscuits 100g");
        // And the scanner sends the barcode whole.
        assertThat(searchNames("8901719100017")).contains("Parle-G Biscuits 100g");
        assertThat(searchNames("1905")).contains("Parle-G Biscuits 100g");
        assertThat(searchNames("zzzz")).isEmpty();
    }

    @Test
    void aRefusedFormNamesEveryBrokenFieldInWords() throws Exception {
        JsonNode problem = postJson("/api/products", managerToken, """
                {"sku":"BAD-1","name":" ","hsnCode":"12","unit":"NOS",
                 "sellingPricePaise":-5,"mrpPaise":0,"taxInclusive":true,"gstRateBp":1800,"cessRateBp":0}
                """, status().isBadRequest());

        assertThat(problem.get("errors").get("name").asText()).isEqualTo("is required");
        assertThat(problem.get("errors").get("hsnCode").asText()).isEqualTo("must be 4, 6 or 8 digits");
        assertThat(problem.get("errors").get("sellingPricePaise").asText()).isEqualTo("cannot be negative");
        assertThat(problem.get("detail").asText())
                .contains("Name is required", "HSN/SAC code must be 4, 6 or 8 digits", "Selling price cannot be negative");
    }

    @Test
    void anItemNeedsNoHsnCode() throws Exception {
        JsonNode product = postJson("/api/products", managerToken, """
                {"sku":"LOOSE-%s","name":"Parotta","unit":"NOS",
                 "sellingPricePaise":2000,"mrpPaise":0,"taxInclusive":true,"gstRateBp":0,"cessRateBp":0}
                """.formatted(TestFixtures.unique()), status().isCreated());
        assertThat(product.get("hsnCode").asText()).isEmpty();

        JsonNode sale = postJson("/api/sales", cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":3000,"discountPaise":0}],
                 "payments":[{"mode":"CASH","amountPaise":6000,"tenderedPaise":6000}],"print":true}
                """.formatted(product.get("id").asText()), status().isCreated());

        assertThat(sale.get("invoice").get("lines").get(0).get("hsnCode").asText()).isEmpty();
        assertThat(Files.readString(latestReceipt())).contains("Parotta").doesNotContain("HSN");
    }

    @Test
    void editingAProductIsKept() throws Exception {
        String productId = newProductWithStock(1_000);

        mvc.perform(authed(put("/api/products/" + productId), managerToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("""
                                {"sku":"ignored","name":"Chicken Rice","hsnCode":"","unit":"NOS",
                                 "sellingPricePaise":12000,"mrpPaise":0,"taxInclusive":true,"gstRateBp":500,
                                 "cessRateBp":0}
                                """))
                .andExpect(status().isOk());

        JsonNode reread = json.readTree(mvc.perform(authed(get("/api/products/" + productId), cashierToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(reread.get("name").asText()).isEqualTo("Chicken Rice");
        assertThat(reread.get("hsnCode").asText()).isEmpty();
        assertThat(reread.get("sellingPricePaise").asLong()).isEqualTo(12_000);
        assertThat(reread.get("gstRateBp").asInt()).isEqualTo(500);

        postJson("/api/products/" + productId + "/deactivate", managerToken, "{}", status().isOk());
        assertThat(json.readTree(mvc.perform(authed(get("/api/products/" + productId), cashierToken))
                        .andReturn().getResponse().getContentAsString())
                .get("active").asBoolean()).isFalse();
    }

    @Test
    void aBarcodeBelongsToOneItemOnly() throws Exception {
        String barcode = "890" + System.nanoTime() % 10_000_000_000L;
        postJson("/api/products", managerToken, """
                {"sku":"BC-%s","barcode":"%s","name":"First","unit":"NOS",
                 "sellingPricePaise":100,"mrpPaise":0,"taxInclusive":true,"gstRateBp":0,"cessRateBp":0}
                """.formatted(TestFixtures.unique(), barcode), status().isCreated());

        JsonNode problem = postJson("/api/products", managerToken, """
                {"sku":"BC-%s","barcode":"%s","name":"Second","unit":"NOS",
                 "sellingPricePaise":100,"mrpPaise":0,"taxInclusive":true,"gstRateBp":0,"cessRateBp":0}
                """.formatted(TestFixtures.unique(), barcode), status().isBadRequest());

        assertThat(problem.get("errors").get("barcode").asText()).contains("already used by First");
    }

    private java.util.List<String> searchNames(String text) throws Exception {
        JsonNode found = json.readTree(mvc.perform(authed(get("/api/products?q=" + text), cashierToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return java.util.stream.StreamSupport.stream(found.spliterator(), false)
                .map(node -> node.get("name").asText())
                .toList();
    }

    private String sellOne(String productId) throws Exception {
        return postJson("/api/sales", cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],
                 "payments":[{"mode":"CASH","amountPaise":10000,"tenderedPaise":10000}],
                 "print":false}
                """.formatted(productId), status().isCreated())
                .get("invoice").get("invoiceNumber").asText();
    }

    private static long sequenceNumberOf(String invoiceNumber) {
        return Long.parseLong(invoiceNumber.substring(invoiceNumber.lastIndexOf('-') + 1));
    }

    /** Creates a product priced at Rs 100.00 inclusive of 18% GST, and puts stock on the shelf. */
    private String newProductWithStock(long quantityMilli) throws Exception {
        String sku = "SKU-" + TestFixtures.unique();
        JsonNode product = postJson("/api/products", managerToken, """
                {"sku":"%s","name":"Biscuits %s","hsnCode":"19053100","unit":"NOS",
                 "sellingPricePaise":10000,"mrpPaise":10000,"taxInclusive":true,"gstRateBp":1800,"cessRateBp":0}
                """.formatted(sku, sku), status().isCreated());
        String productId = product.get("id").asText();
        postJson("/api/stock/receipts", managerToken,
                "{\"productId\":\"%s\",\"quantityMilli\":%d,\"note\":\"Opening\"}".formatted(productId, quantityMilli),
                status().isCreated());
        return productId;
    }

    private long stockOf(String productId) throws Exception {
        return json.readTree(mvc.perform(authed(get("/api/products/" + productId), cashierToken))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("stockMilli").asLong();
    }

    private JsonNode postJson(String path, String token, String body,
                          org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        String response = mvc.perform(authed(post(path), token).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static Path latestReceipt() throws IOException {
        try (Stream<Path> files = Files.list(PRINTER_OUT)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        }
    }
}
