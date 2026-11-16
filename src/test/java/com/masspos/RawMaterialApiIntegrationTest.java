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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The store behind the kitchen: what the shop buys and uses and never sells.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + RawMaterialApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
class RawMaterialApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/raw-material-it";

    private static String managerToken;
    private static String cashierToken;

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
            managerToken = TestAccounts.tokenFor(auth, "store-manager", UserRole.MANAGER);
            cashierToken = TestAccounts.tokenFor(auth, "store-cashier", UserRole.CASHIER);
        }
    }

    /** What the kitchen does to a sack of onions, and what the store says after each of it. */
    @Test
    void buysUsesWastesAndCorrects() throws Exception {
        String id = newMaterial("RM-1", "Onions", "KGS");

        assertThat(move(id, "purchases", """
                {"quantityMilli":25000,"note":"Monday market","buyingPricePaise":3200,
                 "vendorName":"Selvam Vegetables"}""").get("inStoreMilli").asLong()).isEqualTo(25_000);
        assertThat(move(id, "consumption", """
                {"quantityMilli":4000,"note":"Lunch service"}""").get("inStoreMilli").asLong()).isEqualTo(21_000);
        assertThat(move(id, "wastage", """
                {"quantityMilli":1500,"note":"Sprouted"}""").get("inStoreMilli").asLong()).isEqualTo(19_500);
        assertThat(move(id, "corrections", """
                {"deltaMilli":-500,"note":"Counted the store"}""").get("inStoreMilli").asLong()).isEqualTo(19_000);

        JsonNode history = call(get("/api/raw-materials/history?materialId=" + id), managerToken, null,
                status().isOk());
        assertThat(history).hasSize(4);
        assertThat(history.get(0).get("type").asText()).isEqualTo("ADJUSTMENT");
        assertThat(history.get(0).get("balanceAfterMilli").asLong()).isEqualTo(19_000);
        JsonNode bought = history.get(3);
        assertThat(bought.get("type").asText()).isEqualTo("PURCHASE");
        assertThat(bought.get("buyingPricePaise").asLong()).isEqualTo(3_200);
        assertThat(bought.get("vendorName").asText()).isEqualTo("Selvam Vegetables");
        assertThat(bought.get("itemCode").asText()).as("the same shape the shelf's history sends").isEqualTo("RM-1");
    }

    /**
     * The store stops at empty, exactly as the shelf does. A count below zero is not a fact about
     * a kitchen either, and what was actually taken off comes straight back so whoever typed five
     * when there were three can see it.
     */
    @Test
    void takesOutWhatTheStoreHasAndNoMore() throws Exception {
        String id = newMaterial("RM-2", "Butter", "KGS");
        move(id, "purchases", """
                {"quantityMilli":2000,"note":"","buyingPricePaise":0,"vendorName":""}""");

        JsonNode after = move(id, "consumption", """
                {"quantityMilli":5000,"note":"Baking"}""");

        assertThat(after.get("inStoreMilli").asLong()).isZero();
        JsonNode used = call(get("/api/raw-materials/history?materialId=" + id), managerToken, null,
                status().isOk()).get(0);
        assertThat(used.get("quantityDeltaMilli").asLong()).isEqualTo(-2_000);
        assertThat(used.get("note").asText()).contains("the store ran out");
    }

    /**
     * The whole reason raw materials are not a flag on a product: a cashier typing "on" must never
     * be offered Onions among the things they can ring up.
     */
    @Test
    void neverTurnsUpInTheBillingSearch() throws Exception {
        newMaterial("RM-3", "Onion masala", "KGS");

        JsonNode found = call(get("/api/products?q=Onion"), cashierToken, null, status().isOk());

        assertThat(found).isEmpty();
    }

    /** A cashier has no reason to be recording what the kitchen used, and cannot. */
    @Test
    void isAManagersScreen() throws Exception {
        call(get("/api/raw-materials"), cashierToken, null, status().isForbidden());
        call(post("/api/raw-materials"), cashierToken, body("RM-4", "Oil", "LTR"), status().isForbidden());
    }

    /** One code belongs to one material, and the refusal names the one that has it. */
    @Test
    void refusesACodeAnotherMaterialAlreadyUses() throws Exception {
        newMaterial("RM-5", "Chilli powder", "KGS");

        JsonNode refused = call(post("/api/raw-materials"), managerToken, body("RM-5", "Turmeric", "KGS"),
                status().isBadRequest());

        assertThat(refused.at("/errors/code").asText()).contains("Chilli powder");
    }

    /**
     * Removing one frees its code and keeps its movements, which are append-only and are what
     * every count was made of.
     */
    @Test
    void removingOneFreesItsCodeAndKeepsWhatTheStoreDid() throws Exception {
        String id = newMaterial("RM-6", "Cardamom", "KGS");
        move(id, "purchases", """
                {"quantityMilli":1000,"note":"","buyingPricePaise":0,"vendorName":""}""");

        call(delete("/api/raw-materials/" + id), managerToken, null, status().isNoContent());

        assertThat(call(get("/api/raw-materials"), managerToken, null, status().isOk()).toString())
                .doesNotContain("Cardamom");
        String reused = newMaterial("RM-6", "Cardamom, green", "KGS");
        assertThat(reused).isNotEqualTo(id);
        assertThat(call(get("/api/raw-materials/history?materialId=" + id), managerToken, null, status().isOk()))
                .as("what the old one did is still there").hasSize(1);
    }

    private String newMaterial(String code, String name, String unit) throws Exception {
        return call(post("/api/raw-materials"), managerToken, body(code, name, unit), status().isCreated())
                .get("id").asText();
    }

    private static String body(String code, String name, String unit) {
        return """
                {"code":"%s","name":"%s","unit":"%s","reorderLevelMilli":2000,"active":true}"""
                .formatted(code, name, unit);
    }

    private JsonNode move(String id, String what, String body) throws Exception {
        return call(post("/api/raw-materials/" + id + "/" + what), managerToken, body, status().isCreated());
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
