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

    @Test
    void anItemBilledWithoutStockSellsAnyway() throws Exception {
        String productId = newProduct("GUARD-2", "Chicken rice", true);
        assertThat(productOf(productId).get("billWithoutStock").asBoolean()).isTrue();

        sell(productId, status().isCreated());

        // The sale is still recorded as a stock movement; the count simply goes below zero.
        assertThat(stockOf(productId)).isEqualTo(-2_000);
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
        return call(post("/api/sales"), cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":2000,"discountPaise":0}],
                 "payments":[{"mode":"CASH","amountPaise":20000,"tenderedPaise":20000}],"print":false}
                """.formatted(productId), expected);
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
