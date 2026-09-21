package com.masspos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.auth.AuthService;
import com.masspos.license.LicenseService;
import com.masspos.user.UserRole;
import com.masspos.web.LocalApiGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "pos.data-dir=" + LicenseApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
@Import(TestLicensing.Config.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LicenseApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/license-it";
    /** Refused at the licence check, which runs before the body is read; a licensed till says 400 instead. */
    private static final String EMPTY_SALE = "{}";

    private static String adminToken;
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
        if (adminToken == null) {
            adminToken = TestAccounts.tokenFor(auth, "lic-admin", UserRole.ADMIN);
            managerToken = TestAccounts.tokenFor(auth, "lic-manager", UserRole.MANAGER);
            cashierToken = TestAccounts.tokenFor(auth, "lic-cashier", UserRole.CASHIER);
        }
    }

    @Test
    @Order(1)
    void aNewTillCannotBillButEverythingElseWorks() throws Exception {
        JsonNode status = getJson("/api/license", cashierToken);
        assertThat(status.get("state").asText()).isEqualTo("NOT_ACTIVATED");
        assertThat(status.get("billingAllowed").asBoolean()).isFalse();
        assertThat(status.get("machineId").asText()).isEqualTo(TestLicensing.MACHINE_ID);

        JsonNode refused = postJson("/api/sales", cashierToken, EMPTY_SALE, status().isForbidden());
        assertThat(refused.get("title").asText()).isEqualTo("Billing is locked");
        assertThat(refused.get("detail").asText()).contains(TestLicensing.MACHINE_ID);
        assertThat(refused.get("license").asBoolean()).isTrue();
        postJson("/api/returns", managerToken, EMPTY_SALE, status().isForbidden());
        postJson("/api/invoices/0190a7a2-0000-7000-8000-000000000000/replace", managerToken, EMPTY_SALE,
                status().isForbidden());

        // Setting the shop up before the licence arrives is allowed.
        mvc.perform(authed(get("/api/products"), managerToken)).andExpect(status().isOk());
        mvc.perform(authed(get("/api/reports/day"), managerToken)).andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void onlyAnAdminActivates() throws Exception {
        String key = TestLicensing.keyFor(TestLicensing.MACHINE_ID, LocalDate.now().plusYears(1), 15);

        postJson("/api/license", managerToken, body(key), status().isForbidden());
    }

    @Test
    @Order(3)
    void refusesKeysThatWouldNotLicenseThisTill() throws Exception {
        JsonNode otherMachine = postJson("/api/license", adminToken,
                body(TestLicensing.keyFor("ZZZZ-YYYY-XXXX-WWWW", LocalDate.now().plusYears(1), 15)),
                status().isBadRequest());
        assertThat(otherMachine.get("errors").get("key").asText())
                .isEqualTo("is for machine ZZZZ-YYYY-XXXX-WWWW, not this one (%s)".formatted(TestLicensing.MACHINE_ID));
        assertThat(otherMachine.get("detail").asText()).startsWith("Licence key is for machine");

        JsonNode lapsed = postJson("/api/license", adminToken,
                body(TestLicensing.keyFor(TestLicensing.MACHINE_ID, LocalDate.now().minusDays(20), 15)),
                status().isBadRequest());
        assertThat(lapsed.get("errors").get("key").asText()).startsWith("ended on ");

        JsonNode garbage = postJson("/api/license", adminToken, body("not a key"), status().isBadRequest());
        assertThat(garbage.get("errors").get("key").asText()).isEqualTo("is not a Mass POS licence key");

        postJson("/api/license", adminToken, "{\"key\":\"\"}", status().isBadRequest());
        assertThat(getJson("/api/license", adminToken).get("state").asText()).isEqualTo("NOT_ACTIVATED");
    }

    @Test
    @Order(4)
    void aLicenceInItsGracePeriodStillBills() throws Exception {
        JsonNode grace = postJson("/api/license", adminToken,
                body(TestLicensing.keyFor(TestLicensing.MACHINE_ID, LocalDate.now().minusDays(3), 15)),
                status().isOk());

        assertThat(grace.get("state").asText()).isEqualTo("GRACE");
        assertThat(grace.get("billingAllowed").asBoolean()).isTrue();
        postJson("/api/sales", cashierToken, EMPTY_SALE, status().isBadRequest());
    }

    @Test
    @Order(5)
    void renewingReplacesTheInstalledLicence() throws Exception {
        String key = TestLicensing.keyFor(TestLicensing.MACHINE_ID, LocalDate.now().plusYears(1), 30);

        JsonNode renewed = postJson("/api/license", adminToken, body("\n  " + key + "\n"), status().isOk());

        assertThat(renewed.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(renewed.get("customer").asText()).isEqualTo("Sharma General Store");
        assertThat(renewed.get("validUntil").asText()).isEqualTo(LocalDate.now().plusYears(1).toString());
        assertThat(Files.readString(Path.of(DATA_DIR, LicenseService.FILE_NAME)).strip()).isEqualTo(key);
        mvc.perform(authed(get("/api/license"), cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
        postJson("/api/sales", cashierToken, EMPTY_SALE, status().isBadRequest());
    }

    private String body(String key) throws Exception {
        return json.writeValueAsString(java.util.Map.of("key", key));
    }

    private JsonNode getJson(String path, String token) throws Exception {
        return json.readTree(mvc.perform(authed(get(path), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String path, String token, String body, ResultMatcher expected) throws Exception {
        String response = mvc.perform(authed(post(path), token).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? json.nullNode() : json.readTree(response);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
