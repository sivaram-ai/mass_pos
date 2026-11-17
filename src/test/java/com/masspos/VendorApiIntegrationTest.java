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

        // 6 bottles at Rs 20 = Rs 120, and 25 kg of onions at Rs 32 = Rs 800.
        call(post("/api/stock/receipts"), managerToken, """
                {"productId":"%s","quantityMilli":6000,"note":"Van",
                 "buyingPricePaise":2000,"vendorName":"Selvam Traders","vendorId":"%s"}"""
                .formatted(productId, vendorId), status().isCreated());
        call(post("/api/raw-materials/" + materialId + "/purchases"), managerToken, """
                {"quantityMilli":25000,"note":"Monday market","buyingPricePaise":3200,
                 "vendorName":"Selvam Traders","vendorId":"%s"}""".formatted(vendorId), status().isCreated());

        JsonNode profile = call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk());

        assertThat(profile.at("/vendor/boughtPaise").asLong()).isEqualTo(92_000);
        assertThat(profile.at("/vendor/outstandingPaise").asLong()).isEqualTo(92_000);
        assertThat(profile.get("purchases")).hasSize(2);
        assertThat(profile.at("/purchases/0/fromTheStore").asBoolean()).isTrue();
        assertThat(profile.at("/purchases/0/valuePaise").asLong()).isEqualTo(80_000);
        assertThat(profile.at("/purchases/1/valuePaise").asLong()).isEqualTo(12_000);
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
        call(post("/api/stock/receipts"), managerToken, """
                {"productId":"%s","quantityMilli":2000,"note":"","buyingPricePaise":90000,
                 "vendorName":"Gas agency","vendorId":"%s"}""".formatted(productId, vendorId),
                status().isCreated());

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
        call(post("/api/stock/receipts"), managerToken, """
                {"productId":"%s","quantityMilli":10000,"note":"","buyingPricePaise":4500,
                 "vendorName":"Kumar Traders"}""".formatted(productId), status().isCreated());

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
