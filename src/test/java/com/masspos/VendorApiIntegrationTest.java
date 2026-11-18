package com.masspos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.auth.AuthService;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.inventory.InventoryLedgerEvent;
import com.masspos.inventory.InventoryLedgerRepository;
import com.masspos.inventory.LedgerEventType;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The people the shop buys from: what it has had from them, what it has paid, and what is left.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + VendorApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
class VendorApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/vendor-it";

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
    @Autowired
    InventoryLedgerRepository ledger;
    @Autowired
    ProductRepository products;

    @BeforeEach
    void staffOnShift() {
        if (managerToken == null) {
            managerToken = TestAccounts.tokenFor(auth, "vendor-manager", UserRole.MANAGER);
            adminToken = TestAccounts.tokenFor(auth, "vendor-admin", UserRole.ADMIN);
        }
    }

    /**
     * One vendor, one page. A man who sells the shop bottled water for the counter and onions for
     * the kitchen is one vendor, and what the shop owes him is one number.
     */
    @Test
    void gathersWhatWasBoughtAcrossTheShelfAndTheStore() throws Exception {
        String vendorId = newVendor("V-1", "Selvam Traders");
        String productId = newProduct("VP-1", "Bottled water");
        String materialId = newMaterial("VM-1", "Onions");

        // 6 bottles at Rs 20 = Rs 120, and 25 kg of onions at Rs 32 = Rs 800 - one order, because
        // that is how a lorry arrives: things off the shelf and things out of the store together.
        delivered(vendorId, productId, 6_000, 2_000, materialId, 25_000, 3_200);

        JsonNode profile = call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk());

        assertThat(profile.at("/vendor/boughtPaise").asLong()).isEqualTo(92_000);
        assertThat(profile.at("/vendor/outstandingPaise").asLong()).isEqualTo(92_000);
        assertThat(profile.get("purchases")).hasSize(2);
        // Both ends of the shop, in whichever order the two movements landed: they are written in
        // the same breath, so asserting which came first would be asserting the clock.
        List<Long> values = new ArrayList<>();
        boolean fromTheStore = false;
        for (JsonNode purchase : profile.get("purchases")) {
            values.add(purchase.get("valuePaise").asLong());
            fromTheStore |= purchase.get("fromTheStore").asBoolean();
        }
        assertThat(values).containsExactlyInAnyOrder(80_000L, 12_000L);
        assertThat(fromTheStore).as("the store is on his page as well as the shelf").isTrue();
    }

    /**
     * A payment waits for an administrator, and what the shop believes it owes does not move until
     * one agrees. The person who hands money over is rarely the person who decides it is handed
     * over, and a shop where those are the same person has no control at all.
     */
    @Test
    void aPaymentOnlyCountsOnceAnAdministratorHasAgreedToIt() throws Exception {
        String vendorId = newVendor("V-2", "Gas agency");
        String productId = newProduct("VP-2", "Cylinder");
        delivered(vendorId, productId, 2_000, 90_000, null, 0, 0);

        String paymentId = call(post("/api/vendors/" + vendorId + "/payments"), managerToken, """
                {"amountPaise":100000,"paidOn":"2026-11-17","mode":"CASH","reference":"","note":"Part payment"}""",
                status().isCreated()).get("id").asText();

        JsonNode waiting = call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk());
        assertThat(waiting.at("/vendor/waitingPaise").asLong()).isEqualTo(100_000);
        assertThat(waiting.at("/vendor/paidPaise").asLong()).as("nobody has agreed yet").isZero();
        assertThat(waiting.at("/vendor/outstandingPaise").asLong()).isEqualTo(180_000);

        // A manager records it; only an administrator may agree to it.
        call(post("/api/vendors/payments/" + paymentId + "/approve"), managerToken, null, status().isForbidden());
        call(post("/api/vendors/payments/" + paymentId + "/approve"), adminToken, null, status().isNoContent());

        JsonNode settled = call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk());
        assertThat(settled.at("/vendor/paidPaise").asLong()).isEqualTo(100_000);
        assertThat(settled.at("/vendor/waitingPaise").asLong()).isZero();
        assertThat(settled.at("/vendor/outstandingPaise").asLong()).isEqualTo(80_000);
        assertThat(settled.at("/payments/0/approved").asBoolean()).isTrue();
        assertThat(settled.at("/payments/0/approvedBy").asText()).isNotEmpty();
    }

    /**
     * A vendor's history is the balance taken apart: every order written to him, everything that
     * arrived, everything that went back and every rupee handed over, newest first.
     *
     * <p>It is assembled from the records themselves rather than stored, so a delivery and a
     * return are the same ledger row with opposite signs - and the sign is what tells the two
     * apart, which is the one thing this could get wrong.
     */
    @Test
    void tellsTheWholeStoryOfOneVendor() throws Exception {
        String vendorId = newVendor("V-9", "Dairy");
        String productId = newProduct("VP-9", "Butter 500g");
        delivered(vendorId, productId, 10_000, 30_000, null, 0, 0);
        String orderId = call(get("/api/purchase-orders?vendorId=" + vendorId), managerToken, null,
                status().isOk()).get(0).get("id").asText();

        call(post("/api/purchase-returns"), managerToken, """
                {"vendorId":"%s","againstOrderId":"%s","reason":"Two tubs spoiled","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":2000,"unitCostPaise":30000}]}"""
                .formatted(vendorId, orderId, productId), status().isCreated());
        call(post("/api/vendors/" + vendorId + "/payments"), managerToken, """
                {"amountPaise":240000,"paidOn":"2026-11-17","mode":"CASH","reference":"","note":"Settled up"}""",
                status().isCreated());

        JsonNode history = call(get("/api/vendors/" + vendorId + "/history"), managerToken, null,
                status().isOk());

        Map<String, Long> most = new HashMap<>();
        for (JsonNode event : history) {
            most.merge(event.get("kind").asText(), event.get("amountPaise").asLong(), Long::sum);
        }
        assertThat(most).containsOnlyKeys("ORDERED", "BOUGHT", "SENT_BACK", "PAID");
        assertThat(most.get("ORDERED")).as("what the order was written for").isEqualTo(300_000);
        assertThat(most.get("BOUGHT")).as("what arrived").isEqualTo(300_000);
        assertThat(most.get("SENT_BACK")).as("and what went back, as a figure of its own")
                .isEqualTo(60_000);
        assertThat(most.get("PAID")).isEqualTo(240_000);

        // Newest first, and a payment nobody has agreed to says so where the screen can show it.
        List<String> when = new ArrayList<>();
        for (JsonNode event : history) {
            when.add(event.get("when").asText());
        }
        assertThat(when).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(history.get(0).get("kind").asText()).isEqualTo("PAID");
        assertThat(history.get(0).get("state").asText()).isEqualTo("Waiting");
    }

    /**
     * Money can be put against the order it was for, and that order then says so.
     *
     * <p>A vendor's balance is a running figure and answers "what do we owe him"; it cannot answer
     * "has this delivery been paid for", which is the question somebody asks with an invoice in
     * their hand. So a payment may name an order, and the order works out where that leaves it -
     * only from money an administrator has agreed to, exactly as the balance does.
     */
    @Test
    void putsAPaymentAgainstTheOrderItWasFor() throws Exception {
        String vendorId = newVendor("V-7", "Flour mill");
        String productId = newProduct("VP-7", "Flour 10kg");
        delivered(vendorId, productId, 4_000, 50_000, null, 0, 0);
        String orderId = call(get("/api/purchase-orders?vendorId=" + vendorId), managerToken, null,
                status().isOk()).get(0).get("id").asText();

        JsonNode owed = call(get("/api/purchase-orders/" + orderId), managerToken, null, status().isOk());
        assertThat(owed.get("owedPaise").asLong()).as("what actually arrived came to").isEqualTo(200_000);
        assertThat(owed.get("settled").asText()).isEqualTo("UNPAID");

        String paymentId = call(post("/api/vendors/" + vendorId + "/payments"), managerToken, """
                {"amountPaise":120000,"paidOn":"2026-11-17","mode":"UPI","reference":"abc","note":"",
                 "purchaseOrderId":"%s"}""".formatted(orderId), status().isCreated()).get("id").asText();

        assertThat(call(get("/api/purchase-orders/" + orderId), managerToken, null, status().isOk())
                .get("settled").asText())
                .as("nobody has agreed to it yet, so the order has not been paid for")
                .isEqualTo("UNPAID");

        call(post("/api/vendors/payments/" + paymentId + "/approve"), adminToken, null, status().isNoContent());

        JsonNode part = call(get("/api/purchase-orders/" + orderId), managerToken, null, status().isOk());
        assertThat(part.get("paidPaise").asLong()).isEqualTo(120_000);
        assertThat(part.get("settled").asText()).isEqualTo("PART_PAID");

        String rest = call(post("/api/vendors/" + vendorId + "/payments"), managerToken, """
                {"amountPaise":80000,"paidOn":"2026-11-17","mode":"CASH","reference":"","note":"",
                 "purchaseOrderId":"%s"}""".formatted(orderId), status().isCreated()).get("id").asText();
        call(post("/api/vendors/payments/" + rest + "/approve"), adminToken, null, status().isNoContent());

        assertThat(call(get("/api/purchase-orders/" + orderId), managerToken, null, status().isOk())
                .get("settled").asText()).isEqualTo("PAID");
        // And the payment keeps the order it was for, so the vendor's page can name it.
        assertThat(call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk())
                .at("/payments/0/purchaseOrderId").asText()).isEqualTo(orderId);
    }

    /** And agreeing twice to the same money is not a thing. */
    @Test
    void refusesToApproveTheSamePaymentTwice() throws Exception {
        String vendorId = newVendor("V-3", "Packaging wholesaler");
        String paymentId = call(post("/api/vendors/" + vendorId + "/payments"), managerToken, """
                {"amountPaise":5000,"paidOn":"2026-11-17","mode":"UPI","reference":"abc","note":""}""",
                status().isCreated()).get("id").asText();
        call(post("/api/vendors/payments/" + paymentId + "/approve"), adminToken, null, status().isNoContent());

        call(post("/api/vendors/payments/" + paymentId + "/approve"), adminToken, null, status().isConflict());
    }

    /**
     * Deliveries taken before the shop kept a vendor list carry a name and no link. They still
     * belong to the vendor of that name when one is finally written down.
     */
    @Test
    void findsDeliveriesTakenBeforeTheVendorWasOnTheList() throws Exception {
        String productId = newProduct("VP-4", "Sugar 1kg");
        // Written straight to the ledger, because nothing in the app can produce one any more:
        // a delivery now comes in against an order, and an order always names a vendor on the
        // list. This is what an upgraded till's older rows look like - a name and no link.
        Product sugar = products.findById(java.util.UUID.fromString(productId)).orElseThrow();
        ledger.save(new InventoryLedgerEvent(sugar, LedgerEventType.PURCHASE_RECEIPT, 10_000, null, "T1",
                java.time.Instant.now(), null, "", 4_500, "Kumar Traders", null, ""));

        String vendorId = newVendor("V-4", "Kumar Traders");

        JsonNode profile = call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk());
        assertThat(profile.get("purchases")).hasSize(1);
        assertThat(profile.at("/vendor/boughtPaise").asLong()).isEqualTo(45_000);
        assertThat(call(get("/api/vendors"), managerToken, null, status().isOk()).toString())
                .as("and on the list, where the shop sees what it owes")
                .contains("Kumar Traders");
    }

    /** One code belongs to one vendor, and a GSTIN that is given has to be a real one. */
    @Test
    void refusesATakenCodeAndANonsenseGstin() throws Exception {
        newVendor("V-5", "Fruit man");

        assertThat(call(post("/api/vendors"), managerToken, """
                {"code":"V-5","name":"Someone else","phone":"","gstin":"","address":"","note":"","active":true}""",
                status().isBadRequest()).at("/errors/code").asText()).contains("Fruit man");

        assertThat(call(post("/api/vendors"), managerToken, """
                {"code":"V-6","name":"Dairy","phone":"","gstin":"NOTAGSTIN","address":"","note":"","active":true}""",
                status().isBadRequest()).at("/errors/gstin").asText()).contains("valid GSTIN");
    }

    /**
     * Goods arriving, the only way they can now: a manager writes an order, an administrator
     * agrees to the money, and the lorry turns up. The vendor and the price reach both ledgers
     * through the order, which is what a vendor's page is gathering.
     *
     * @param materialId a second line out of the store, or null for an order off the shelf alone
     */
    private void delivered(String vendorId, String productId, long quantityMilli, long unitCostPaise,
                           String materialId, long storeMilli, long storeCostPaise) throws Exception {
        String lines = """
                {"productId":"%s","rawMaterialId":null,"quantityMilli":%d,"unitCostPaise":%d}"""
                .formatted(productId, quantityMilli, unitCostPaise);
        if (materialId != null) {
            lines += ", " + """
                    {"productId":null,"rawMaterialId":"%s","quantityMilli":%d,"unitCostPaise":%d}"""
                    .formatted(materialId, storeMilli, storeCostPaise);
        }
        String orderId = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[%s]}""".formatted(vendorId, lines),
                status().isCreated()).get("id").asText();
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}", status().isOk());
    }

    private String newVendor(String code, String name) throws Exception {
        return call(post("/api/vendors"), managerToken, """
                {"code":"%s","name":"%s","phone":"98400 00000","gstin":"","address":"","note":"","active":true}"""
                .formatted(code, name), status().isCreated()).get("id").asText();
    }

    private String newProduct(String sku, String name) throws Exception {
        return call(post("/api/products"), managerToken, """
                {"sku":"%s","name":"%s","hsnCode":"","unit":"NOS","sellingPricePaise":10000,"mrpPaise":0,
                 "taxInclusive":true,"gstRateBp":0,"cessRateBp":0,"billWithoutStock":true}"""
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
