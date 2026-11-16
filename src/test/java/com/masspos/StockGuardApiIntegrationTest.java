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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A shop that refuses to sell what the ledger says it does not have, and the items that are exempt:
 * cooked food, loose produce and anything else whose stock is never counted.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + StockGuardApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.billing.block-negative-stock=true",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
@Import(TestLicensing.Config.class)
class StockGuardApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/stock-guard-it";

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
            cashierToken = TestAccounts.tokenFor(auth, "guard-cashier", UserRole.CASHIER);
            managerToken = TestAccounts.tokenFor(auth, "guard-manager", UserRole.MANAGER);
        }
    }

    @Test
    void anItemWithoutStockCannotBeSold() throws Exception {
        String productId = newProduct("GUARD-1", "Sugar 1kg", false);

        JsonNode refused = sell(productId, status().isBadRequest());

        assertThat(refused.get("detail").asText()).contains("Not enough stock for Sugar 1kg");
        assertThat(stockOf(productId)).isZero();
    }

    /**
     * The sale goes through - that is what the exemption is for - and the count stops at empty.
     *
     * <p>It used to go below zero, on the reasoning that the goods had left the shop so the ledger
     * should say so. In a kitchen it compounds instead: one restaurant's screen read -357 of a
     * biriyani it cooks to order, which tells nobody anything and makes every figure beside it
     * worth ignoring. A count below zero is not a fact about a shop.
     */
    @Test
    void anItemBilledWithoutStockSellsAnywayAndTheCountStopsAtEmpty() throws Exception {
        String productId = newProduct("GUARD-2", "Chicken rice", true);
        assertThat(productOf(productId).get("billWithoutStock").asBoolean()).isTrue();

        sell(productId, status().isCreated());
        sell(productId, status().isCreated());

        assertThat(stockOf(productId)).isZero();
    }

    /**
     * Part of a sale can come off a shelf that runs out halfway through it. What came off is three
     * of the five sold, and the bill still says five: the bill is the record of what was sold, and
     * this is the record of what the shelf had.
     */
    @Test
    void takesOffWhatTheShelfHasAndNoMore() throws Exception {
        String productId = newProduct("GUARD-4", "Idli", true);
        receive(productId, 3_000);

        JsonNode sale = sell(productId, 5_000, status().isCreated());

        assertThat(stockOf(productId)).isZero();
        assertThat(sale.at("/invoice/lines/0/quantityMilli").asLong())
                .as("the customer was billed for all five").isEqualTo(5_000);
    }

    /**
     * Cancelling puts back what the bill took off, not what it sold. Reversing the quantity on the
     * bill would leave two on a shelf that was empty when it was billed from.
     */
    @Test
    void cancellingPutsBackOnlyWhatTheBillTookOff() throws Exception {
        String productId = newProduct("GUARD-5", "Vada", true);
        receive(productId, 3_000);
        String invoiceId = sell(productId, 5_000, status().isCreated()).get("invoice").get("id").asText();

        call(post("/api/invoices/" + invoiceId + "/cancel"), managerToken,
                "{\"reason\":\"Customer changed their mind\"}", status().isOk());

        assertThat(stockOf(productId)).isEqualTo(3_000);
    }

    /**
     * Where a count came from. A number on its own has to be taken on trust; this is what somebody
     * holding six of something the screen calls four actually opens.
     */
    @Test
    void tellsTheWholeStoryOfOneItemsCount() throws Exception {
        String productId = newProduct("GUARD-6", "Dosa", true);
        receive(productId, 3_000);
        sell(productId, 5_000, status().isCreated());

        JsonNode history = call(get("/api/stock/history?productId=" + productId), managerToken, null, status().isOk());

        assertThat(history).hasSize(2);
        JsonNode sold = history.get(0);
        assertThat(sold.get("type").asText()).as("newest first").isEqualTo("SALE");
        assertThat(sold.get("quantityDeltaMilli").asLong()).as("only what the shelf had").isEqualTo(-3_000);
        assertThat(sold.get("balanceAfterMilli").asLong()).isZero();
        assertThat(sold.get("note").asText()).as("which bill took them").startsWith("Bill ");
        assertThat(sold.get("by").asText()).isNotEmpty();

        JsonNode received = history.get(1);
        assertThat(received.get("type").asText()).isEqualTo("PURCHASE_RECEIPT");
        assertThat(received.get("quantityDeltaMilli").asLong()).isEqualTo(3_000);
        assertThat(received.get("balanceAfterMilli").asLong())
                .as("what the shelf held after the goods came in").isEqualTo(3_000);
        assertThat(received.get("name").asText()).isEqualTo("Dosa");
    }

    /** Over a window across every item there is no honest running count, so none is offered. */
    @Test
    void leavesTheRunningCountOutOfAWindowThatStartsInTheMiddle() throws Exception {
        String productId = newProduct("GUARD-7", "Pongal", true);
        receive(productId, 1_000);

        JsonNode history = call(get("/api/stock/history?days=7"), managerToken, null, status().isOk());

        assertThat(history.isArray()).isTrue();
        assertThat(history).isNotEmpty();
        for (JsonNode row : history) {
            assertThat(row.get("balanceAfterMilli").isNull()).isTrue();
        }
    }

    private void receive(String productId, long quantityMilli) throws Exception {
        call(post("/api/stock/receipts"), managerToken, """
                {"productId":"%s","quantityMilli":%d,"note":"Opening"}"""
                .formatted(productId, quantityMilli), status().isCreated());
    }


    @Test
    void theExemptionIsTurnedOnAndOffWhenTheItemIsEdited() throws Exception {
        String productId = newProduct("GUARD-3", "Parotta", false);

        call(put("/api/products/" + productId), managerToken, body("GUARD-3", "Parotta", true), status().isOk());
        assertThat(productOf(productId).get("billWithoutStock").asBoolean()).isTrue();
        sell(productId, status().isCreated());

        call(put("/api/products/" + productId), managerToken, body("GUARD-3", "Parotta", false), status().isOk());
        assertThat(productOf(productId).get("billWithoutStock").asBoolean()).isFalse();
        assertThat(sell(productId, status().isBadRequest()).get("detail").asText()).contains("Not enough stock");
    }

    private static String body(String sku, String name, boolean billWithoutStock) {
        return """
                {"sku":"%s","name":"%s","hsnCode":"","unit":"NOS","sellingPricePaise":10000,"mrpPaise":0,
                 "taxInclusive":true,"gstRateBp":0,"cessRateBp":0,"billWithoutStock":%s}
                """.formatted(sku, name, billWithoutStock);
    }

    private String newProduct(String sku, String name, boolean billWithoutStock) throws Exception {
        return call(post("/api/products"), managerToken, body(sku, name, billWithoutStock), status().isCreated())
                .get("id").asText();
    }

    private JsonNode sell(String productId, ResultMatcher expected) throws Exception {
        return sell(productId, 2_000, expected);
    }

    /** Everything here is priced at 10000 paise the unit, so the cash due is the quantity x 10. */
    private JsonNode sell(String productId, long quantityMilli, ResultMatcher expected) throws Exception {
        long due = quantityMilli * 10;
        return call(post("/api/sales"), cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":%d,"discountPaise":0}],
                 "payments":[{"mode":"CASH","amountPaise":%d,"tenderedPaise":%d}],"print":false}
                """.formatted(productId, quantityMilli, due, due), expected);
    }

    private JsonNode productOf(String productId) throws Exception {
        return call(get("/api/products/" + productId), cashierToken, null, status().isOk());
    }

    private long stockOf(String productId) throws Exception {
        return productOf(productId).get("stockMilli").asLong();
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
