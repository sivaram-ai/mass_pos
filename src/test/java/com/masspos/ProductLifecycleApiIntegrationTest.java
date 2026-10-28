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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stopping, starting and deleting an item. A stopped item stays on the Products and Stock screens
 * and only leaves billing; a deleted one leaves every screen and gives up its code, while the bills
 * that sold it still read it, because deleting the row itself is impossible: the invoice lines and
 * ledger events pointing at it are frozen by the append-only triggers.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + ProductLifecycleApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
@Import(TestLicensing.Config.class)
class ProductLifecycleApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/product-lifecycle-it";

    private static String cashierToken;
    private static String managerToken;

    static {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
        TestLicensing.install(DATA_DIR);
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
            cashierToken = TestAccounts.tokenFor(auth, "life-cashier", UserRole.CASHIER);
            managerToken = TestAccounts.tokenFor(auth, "life-manager", UserRole.MANAGER);
        }
    }

    @Test
    void aStoppedItemStaysOnTheProductsAndStockScreensAndOnlyLeavesBilling() throws Exception {
        String id = newProduct("LIFE-1", "Sugar 1kg", "8901234500011");

        stop(id);

        assertThat(productOf(id).get("active").asBoolean()).isFalse();
        // The two screens a manager would use to find it again still show it.
        assertThat(catalogueCodes()).contains("LIFE-1");
        assertThat(stockCodes()).contains("LIFE-1");
        // Billing does not: a stopped item is not sellable.
        assertThat(billingCodes("LIFE-1")).doesNotContain("LIFE-1");
    }

    @Test
    void startingItPutsItBackIntoBilling() throws Exception {
        String id = newProduct("LIFE-2", "Tea 250g", null);

        stop(id);
        assertThat(billingCodes("LIFE-2")).doesNotContain("LIFE-2");

        call(post("/api/products/" + id + "/activate"), managerToken, null, status().isOk());

        assertThat(productOf(id).get("active").asBoolean()).isTrue();
        assertThat(billingCodes("LIFE-2")).contains("LIFE-2");
    }

    @Test
    void aDeletedItemIsGoneFromEveryListAndFromTheCatalogueSheet() throws Exception {
        String id = newProduct("LIFE-3", "Soap bar", "8901234500033");

        call(delete("/api/products/" + id), managerToken, null, status().isNoContent());

        assertThat(catalogueCodes()).doesNotContain("LIFE-3");
        assertThat(stockCodes()).doesNotContain("LIFE-3");
        assertThat(billingCodes("LIFE-3")).doesNotContain("LIFE-3");
        assertThat(catalogueSheet()).doesNotContain("LIFE-3", "Soap bar");
    }

    @Test
    void aNewItemCanTakeADeletedItemsCodeAndBarcode() throws Exception {
        String first = newProduct("LIFE-4", "Biscuits old", "8901234500044");
        // Taken, until it is deleted.
        assertThat(create("LIFE-4", "Biscuits clash", null, status().isBadRequest()).get("errors").get("sku").asText())
                .contains("already used");

        call(delete("/api/products/" + first), managerToken, null, status().isNoContent());

        String second = newProduct("LIFE-4", "Biscuits new", "8901234500044");
        assertThat(second).isNotEqualTo(first);
        assertThat(productOf(second).get("sku").asText()).isEqualTo("LIFE-4");
        // The deleted row gave up the code rather than keeping it.
        assertThat(productOf(first).get("sku").asText()).startsWith("~").isNotEqualTo("LIFE-4");
        assertThat(productOf(first).get("barcode").isNull()).isTrue();
    }

    @Test
    void theBillThatSoldADeletedItemStillReadsTheCodeItWasSoldUnder() throws Exception {
        String id = newProduct("LIFE-5", "Coffee 100g", null);
        receive(id, 5_000);
        String invoiceId = sell(id).get("invoice").get("id").asText();

        call(delete("/api/products/" + id), managerToken, null, status().isNoContent());

        JsonNode line = call(get("/api/invoices/" + invoiceId), managerToken, null, status().isOk())
                .get("lines").get(0);
        assertThat(line.get("sku").asText()).isEqualTo("LIFE-5");
        assertThat(line.get("name").asText()).isEqualTo("Coffee 100g");
    }

    @Test
    void deletingLeavesTheStockLedgerAloneAndTheReusedCodeStartsAtNothing() throws Exception {
        String id = newProduct("LIFE-6", "Rice 5kg", null);
        receive(id, 10_000);
        assertThat(stockOf(id)).isEqualTo(10_000);

        call(delete("/api/products/" + id), managerToken, null, status().isNoContent());

        // The movements are frozen, so the count is exactly what it was: nothing was unwound.
        assertThat(stockOf(id)).isEqualTo(10_000);
        // A new item under the old code is a different item, and starts empty.
        assertThat(stockOf(newProduct("LIFE-6", "Rice 5kg new", null))).isZero();
    }

    private static String body(String sku, String name, String barcode) {
        return """
                {"sku":"%s","name":"%s","barcode":%s,"hsnCode":"","unit":"NOS","sellingPricePaise":10000,
                 "mrpPaise":0,"taxInclusive":true,"gstRateBp":0,"cessRateBp":0,"billWithoutStock":false}
                """.formatted(sku, name, barcode == null ? "null" : "\"" + barcode + "\"");
    }

    private JsonNode create(String sku, String name, String barcode, ResultMatcher expected) throws Exception {
        return call(post("/api/products"), managerToken, body(sku, name, barcode), expected);
    }

    private String newProduct(String sku, String name, String barcode) throws Exception {
        return create(sku, name, barcode, status().isCreated()).get("id").asText();
    }

    private void stop(String id) throws Exception {
        call(post("/api/products/" + id + "/deactivate"), managerToken, null, status().isOk());
    }

    private void receive(String id, long quantityMilli) throws Exception {
        call(post("/api/stock/receipts"), managerToken,
                """
                {"productId":"%s","quantityMilli":%d,"note":"opening"}
                """.formatted(id, quantityMilli), status().isCreated());
    }

    private JsonNode sell(String id) throws Exception {
        return call(post("/api/sales"), cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],
                 "payments":[{"mode":"CASH","amountPaise":10000,"tenderedPaise":10000}],"print":false}
                """.formatted(id), status().isCreated());
    }

    /** What the Products screen lists: everything the shop still has, stopped items included. */
    private List<String> catalogueCodes() throws Exception {
        return codesOf(call(get("/api/products?all=true"), managerToken, null, status().isOk()), "sku");
    }

    /** What the billing grid sees for a typed code: sellable items only. */
    private List<String> billingCodes(String text) throws Exception {
        return codesOf(call(get("/api/products?q=" + text), cashierToken, null, status().isOk()), "sku");
    }

    private List<String> stockCodes() throws Exception {
        return codesOf(call(get("/api/stock"), managerToken, null, status().isOk()), "sku");
    }

    private String catalogueSheet() throws Exception {
        return mvc.perform(get("/api/products/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<String> codesOf(JsonNode rows, String field) {
        List<String> codes = new ArrayList<>();
        rows.forEach(row -> codes.add(row.get(field).asText()));
        return codes;
    }

    private JsonNode productOf(String id) throws Exception {
        return call(get("/api/products/" + id), managerToken, null, status().isOk());
    }

    private long stockOf(String id) throws Exception {
        return productOf(id).get("stockMilli").asLong();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String token, String body, ResultMatcher expected)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(LocalApiGuard.CLIENT_HEADER, "test");
        if (body != null) {
            request.contentType(APPLICATION_JSON).content(body);
        }
        String response = mvc.perform(request).andExpect(expected).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? json.nullNode() : json.readTree(response);
    }
}
