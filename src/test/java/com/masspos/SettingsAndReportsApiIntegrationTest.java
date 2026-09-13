package com.masspos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.auth.AuthService;
import com.masspos.user.UserRole;
import com.masspos.web.LocalApiGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "pos.data-dir=" + SettingsAndReportsApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.terminal.name=Counter 1",
        "pos.terminal.section=Ground floor"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettingsAndReportsApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/settings-it";

    private static String adminToken;
    private static String cashierToken;
    private static String auditorToken;

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
        if (adminToken == null) {
            adminToken = TestAccounts.tokenFor(auth, "set-admin", UserRole.ADMIN);
            cashierToken = TestAccounts.tokenFor(auth, "set-cashier", UserRole.CASHIER);
            auditorToken = TestAccounts.tokenFor(auth, "set-auditor", UserRole.AUDITOR);
        }
    }

    @Test
    @Order(1)
    void aFreshShopSaysWhatItNeedsBeforeBilling() throws Exception {
        mvc.perform(as(get("/api/settings"), cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToInvoice").value(false))
                .andExpect(jsonPath("$.missingForInvoicing").isNotEmpty())
                .andExpect(jsonPath("$.thisTerminal.name").value("Counter 1"))
                .andExpect(jsonPath("$.thisTerminal.section").value("Ground floor"));
    }

    @Test
    @Order(2)
    void billingIsRefusedUntilTheGstDetailsAreFilledIn() throws Exception {
        // 409, not 500: the request was fine, the shop is not set up yet.
        mvc.perform(as(post("/api/sales"), cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("""
                                {"lines":[{"productId":"00000000-0000-0000-0000-000000000001","quantityMilli":1000,
                                  "discountPaise":0}],
                                 "payments":[{"mode":"CASH","amountPaise":1000,"tenderedPaise":1000}],"print":false}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(3)
    void onlyAnAdminMayChangeTheShopDetails() throws Exception {
        mvc.perform(as(put("/api/settings"), cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(settingsJson("27AAPFU0939F1ZV")))
                .andExpect(status().isForbidden());

        mvc.perform(as(put("/api/settings"), adminToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(settingsJson("27AAPFU0939F1ZV")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToInvoice").value(true))
                .andExpect(jsonPath("$.shop.tradeName").value("MASS MART"))
                .andExpect(jsonPath("$.shop.serviceProviderName").value("Ravi Systems"));
    }

    @Test
    @Order(4)
    void aMistypedGstinIsRefusedOnScreenToo() throws Exception {
        mvc.perform(as(put("/api/settings"), adminToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(settingsJson("27AAPFU0939F1ZW")
                                .replace("shop@example.com", "shop at example")))
                .andExpect(status().isBadRequest())
                // Every broken field, in words, not a bare "Bad Request".
                .andExpect(jsonPath("$.errors.gstin").value("is not a valid GSTIN (wrong format or check character)"))
                .andExpect(jsonPath("$.errors.email").value("is not a valid email address"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("GSTIN is not a valid GSTIN"),
                        org.hamcrest.Matchers.containsString("Email is not a valid email address"))));
    }

    @Test
    @Order(5)
    void theDaysTakingsAddUpAndTheAuditTrailShowsWhoDidWhat() throws Exception {
        // Configure the shop, then actually sell something.
        mvc.perform(as(put("/api/settings"), adminToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(settingsJson("27AAPFU0939F1ZV")))
                .andExpect(status().isOk());
        String productId = json.readTree(mvc.perform(as(post("/api/products"), adminToken)
                                .header(LocalApiGuard.CLIENT_HEADER, "test").contentType(APPLICATION_JSON)
                                .content("""
                                        {"sku":"RPT-1","name":"Sugar 1kg","hsnCode":"17019100","unit":"KGS",
                                         "sellingPricePaise":5000,"mrpPaise":5000,"taxInclusive":true,
                                         "gstRateBp":500,"cessRateBp":0}
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(as(post("/api/sales"), cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("""
                                {"lines":[{"productId":"%s","quantityMilli":2000,"discountPaise":0}],
                                 "payments":[{"mode":"CASH","amountPaise":10000,"tenderedPaise":10000}],
                                 "print":false}
                                """.formatted(productId)))
                .andExpect(status().isCreated());

        // An auditor reads the books but cannot touch them.
        JsonNode day = json.readTree(mvc.perform(as(get("/api/reports/day"), auditorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(day.get("bills").asLong()).isEqualTo(1);
        assertThat(day.get("totals").get("grandTotalPaise").asLong()).isEqualTo(10_000);
        assertThat(day.get("payments").get(0).get("mode").asText()).isEqualTo("CASH");
        assertThat(day.get("terminals").get(0).get("name").asText()).isEqualTo("T1");
        assertThat(day.get("bestSellers").get(0).get("name").asText()).isEqualTo("Sugar 1kg");

        JsonNode gst = json.readTree(mvc.perform(as(get("/api/reports/gst"), auditorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(gst.get("rates").get(0).get("gstRateBp").asInt()).isEqualTo(500);
        assertThat(gst.get("rates").get(0).get("taxableValuePaise").asLong()).isEqualTo(9_524);
        assertThat(gst.get("hsn").get(0).get("hsnCode").asText()).isEqualTo("17019100");
        assertThat(gst.get("hsn").get(0).get("uqc").asText()).isEqualTo("KGS");

        JsonNode audit = json.readTree(mvc.perform(as(get("/api/reports/audit"), auditorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(audit).isNotEmpty();
        assertThat(audit.findValuesAsText("entity")).contains("invoice", "shop_settings");
        assertThat(audit.findValuesAsText("user")).contains("set-cashier", "set-admin");

        // A cashier has no business reading the day's takings.
        mvc.perform(as(get("/api/reports/day"), cashierToken)).andExpect(status().isForbidden());
    }

    @Test
    @Order(6)
    void aShopWithoutGstinBillsWithoutTax() throws Exception {
        mvc.perform(as(put("/api/settings"), adminToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(settingsJson("").replace("U52100MH2020PTC123456", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToInvoice").value(true))
                .andExpect(jsonPath("$.gstRegistered").value(false));

        // 18% on top of Rs 100.00: a GST shop would charge Rs 118.00, a shop without a GSTIN charges Rs 100.00.
        String productId = json.readTree(mvc.perform(as(post("/api/products"), adminToken)
                                .header(LocalApiGuard.CLIENT_HEADER, "test").contentType(APPLICATION_JSON)
                                .content("""
                                        {"sku":"NOGST-1","name":"Egg Parotta","unit":"NOS",
                                         "sellingPricePaise":10000,"mrpPaise":0,"taxInclusive":false,
                                         "gstRateBp":1800,"cessRateBp":0}
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(as(post("/api/sales/quote"), cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("""
                                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}]}
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotalPaise").value(10_000))
                .andExpect(jsonPath("$.cgstPaise").value(0));

        // There is no GST to claim from a shop that charges none.
        mvc.perform(as(post("/api/sales"), cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("""
                                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],
                                 "payments":[{"mode":"CASH","amountPaise":10000,"tenderedPaise":10000}],
                                 "buyerGstin":"29AABCU9603R1ZJ","print":false}
                                """.formatted(productId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("GSTIN")));

        JsonNode invoice = json.readTree(mvc.perform(as(post("/api/sales"), cashierToken)
                                .header(LocalApiGuard.CLIENT_HEADER, "test").contentType(APPLICATION_JSON)
                                .content("""
                                        {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],
                                         "payments":[{"mode":"CASH","amountPaise":10000,"tenderedPaise":10000}],
                                         "print":true}
                                        """.formatted(productId)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("invoice");
        assertThat(invoice.get("taxInvoice").asBoolean()).isFalse();
        assertThat(invoice.get("taxableValuePaise").asLong()).isEqualTo(10_000);
        assertThat(invoice.get("cgstPaise").asLong() + invoice.get("sgstPaise").asLong()).isZero();
        assertThat(invoice.get("lines").get(0).get("gstRateBp").asInt()).isZero();

        String receipt;
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of(DATA_DIR, "printer-out"))) {
            receipt = java.nio.file.Files.readString(files.filter(p -> p.toString().endsWith(".txt"))
                    .max(java.util.Comparator.naturalOrder()).orElseThrow());
        }
        assertThat(receipt).contains("MASS MART", "BILL", "Egg Parotta", "TOTAL Rs.", "100.00")
                .doesNotContain("TAX INVOICE", "GSTIN", "CGST", "CIN:", "Place of supply", "Reverse charge");
    }

    private static String settingsJson(String gstin) {
        return """
                {"legalName":"Mass Retail Private Limited","tradeName":"MASS MART","gstin":"%s",
                 "address":["12 MG Road, Camp","Pune 411001"],"phone":"020-5550100",
                 "email":"shop@example.com","website":"www.example.com",
                 "cin":"U52100MH2020PTC123456","fssaiLicense":"11521999000123","upiVpa":"massmart@okicici",
                 "receiptFooter":["Exchange within 7 days","Thank you!"],
                 "serviceProviderName":"Ravi Systems","servicePhone":"98200-00000",
                 "serviceEmail":"support@example.com","roundInvoiceTotal":true,"blockNegativeStock":false}
                """.formatted(gstin);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
