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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ordering goods: asking, agreeing, and taking them in - and the gap between the three, which is
 * the whole reason an order exists as a separate thing from a delivery.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + PurchaseOrderApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
class PurchaseOrderApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/purchase-order-it";

    private static String managerToken;
    private static String adminToken;

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
        if (managerToken == null) {
            managerToken = TestAccounts.tokenFor(auth, "po-manager", UserRole.MANAGER);
            adminToken = TestAccounts.tokenFor(auth, "po-admin", UserRole.ADMIN);
        }
    }

    /**
     * The whole way through: a manager asks, an administrator agrees, the lorry arrives, and only
     * then do the counts move - on the shelf and in the store, from one order.
     */
    @Test
    void asksAgreesAndTakesTheGoodsIn() throws Exception {
        String vendorId = newVendor("PV-1", "Selvam Traders");
        String productId = newProduct("PP-1", "Bottled water");
        String materialId = newMaterial("PM-1", "Onions");

        JsonNode order = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"Weekly order","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":12000,"unitCostPaise":2000},
                  {"productId":null,"rawMaterialId":"%s","quantityMilli":25000,"unitCostPaise":3200}]}"""
                .formatted(vendorId, productId, materialId), status().isCreated());
        String orderId = order.get("id").asText();

        assertThat(order.get("number").asText()).startsWith("PO-");
        assertThat(order.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(order.get("valuePaise").asLong()).isEqualTo(24_000 + 80_000);
        assertThat(order.get("createdBy").asText()).isNotEmpty();

        // Nothing has moved yet: an order is a plan.
        assertThat(stockOf(productId)).isZero();

        // Goods cannot be taken in against an order nobody has agreed to.
        assertThat(call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}",
                status().isConflict()).get("detail").asText()).contains("has not been approved");

        // A manager may not agree to the money; an administrator may.
        call(post("/api/purchase-orders/" + orderId + "/approve"), managerToken, null, status().isForbidden());
        assertThat(call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk())
                .get("status").asText()).isEqualTo("APPROVED");

        JsonNode received = call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}",
                status().isOk());

        assertThat(received.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(received.get("receivedBy").asText()).isNotEmpty();
        assertThat(stockOf(productId)).as("the shelf moved when the goods came, not before").isEqualTo(12_000);
        assertThat(inStore(materialId)).isEqualTo(25_000);

        // And the delivery reads like any other, with the order on it.
        JsonNode movement = call(get("/api/stock/history?productId=" + productId), managerToken, null,
                status().isOk()).get(0);
        assertThat(movement.get("type").asText()).isEqualTo("PURCHASE_RECEIPT");
        assertThat(movement.get("buyingPricePaise").asLong()).isEqualTo(2_000);
        assertThat(movement.get("vendorName").asText()).isEqualTo("Selvam Traders");
        assertThat(movement.get("note").asText()).contains("PO-");

        // The vendor is owed what actually arrived.
        assertThat(call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk())
                .at("/vendor/outstandingPaise").asLong()).isEqualTo(104_000);
    }

    /** The lorry brought eight of the twelve. What is recorded is what came. */
    @Test
    void recordsWhatActuallyArrived() throws Exception {
        String vendorId = newVendor("PV-2", "Dairy");
        String productId = newProduct("PP-2", "Butter pack");
        String orderId = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":12000,"unitCostPaise":5000}]}"""
                .formatted(vendorId, productId), status().isCreated()).get("id").asText();
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        String lineId = call(get("/api/purchase-orders/" + orderId), managerToken, null, status().isOk())
                .at("/lines/0/id").asText();

        JsonNode received = call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, """
                {"arrived":[{"lineId":"%s","quantityMilli":8000,"unitCostPaise":5200}]}""".formatted(lineId),
                status().isOk());

        assertThat(received.at("/lines/0/quantityMilli").asLong()).as("what was asked for").isEqualTo(12_000);
        assertThat(received.at("/lines/0/receivedMilli").asLong()).as("what came").isEqualTo(8_000);
        assertThat(stockOf(productId)).isEqualTo(8_000);
        assertThat(call(get("/api/stock/history?productId=" + productId), managerToken, null, status().isOk())
                .get(0).get("buyingPricePaise").asLong()).as("at what it actually cost").isEqualTo(5_200);
    }

    /** Turning one down needs a reason, and nothing further happens to it. */
    @Test
    void refusingOneNeedsAReasonAndEndsIt() throws Exception {
        String vendorId = newVendor("PV-3", "Packaging");
        String productId = newProduct("PP-3", "Carrier bags");
        String orderId = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":1000,"unitCostPaise":100}]}"""
                .formatted(vendorId, productId), status().isCreated()).get("id").asText();

        call(post("/api/purchase-orders/" + orderId + "/reject"), adminToken, "{\"decision\":\"\"}",
                status().isBadRequest());

        JsonNode rejected = call(post("/api/purchase-orders/" + orderId + "/reject"), adminToken,
                "{\"decision\":\"Too many in the back already\"}", status().isOk());
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("decision").asText()).contains("back already");

        assertThat(call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}",
                status().isConflict()).get("detail").asText()).contains("rejected");
        assertThat(call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null,
                status().isConflict()).get("detail").asText()).contains("already been decided");
    }

    /** What an administrator has waiting, which is the queue they work through. */
    @Test
    void listsWhatIsWaitingOnAnAdministrator() throws Exception {
        String vendorId = newVendor("PV-4", "Fruit man");
        String productId = newProduct("PP-4", "Bananas");
        call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":5000,"unitCostPaise":4000}]}"""
                .formatted(vendorId, productId), status().isCreated());

        JsonNode waiting = call(get("/api/purchase-orders/waiting"), adminToken, null, status().isOk());

        assertThat(waiting).isNotEmpty();
        for (JsonNode order : waiting) {
            assertThat(order.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        }
    }

    private long stockOf(String productId) throws Exception {
        return call(get("/api/products/" + productId), managerToken, null, status().isOk())
                .get("stockMilli").asLong();
    }

    private long inStore(String materialId) throws Exception {
        for (JsonNode row : call(get("/api/raw-materials"), managerToken, null, status().isOk())) {
            if (row.get("id").asText().equals(materialId)) {
                return row.get("inStoreMilli").asLong();
            }
        }
        return 0;
    }

    private String newVendor(String code, String name) throws Exception {
        return call(post("/api/vendors"), managerToken, """
                {"code":"%s","name":"%s","phone":"","gstin":"","address":"","note":"","active":true}"""
                .formatted(code, name), status().isCreated()).get("id").asText();
    }

    private String newProduct(String sku, String name) throws Exception {
        return call(post("/api/products"), managerToken, """
                {"sku":"%s","name":"%s","hsnCode":"","unit":"NOS","sellingPricePaise":10000,"mrpPaise":0,
                 "taxInclusive":true,"gstRateBp":0,"cessRateBp":0,"billWithoutStock":false}"""
                .formatted(sku, name), status().isCreated()).get("id").asText();
    }

    private String newMaterial(String code, String name) throws Exception {
        return call(post("/api/raw-materials"), managerToken, """
                {"code":"%s","name":"%s","unit":"KGS","reorderLevelMilli":0,"active":true}"""
                .formatted(code, name), status().isCreated()).get("id").asText();
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
