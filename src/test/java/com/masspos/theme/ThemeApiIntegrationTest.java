package com.masspos.theme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.TestAccounts;
import com.masspos.TestLicensing;
import com.masspos.auth.AuthService;
import com.masspos.user.UserRole;
import com.masspos.web.LocalApiGuard;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shop's theme as the counters meet it: everyone signed in reads the same one, only an
 * administrator changes it, and a change shows up as a new revision for the counters to notice.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + ThemeApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.terminal.code=T1"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(TestLicensing.Config.class)
class ThemeApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/theme-it";

    static {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
        TestLicensing.install(DATA_DIR);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AuthService auth;

    private final ObjectMapper json = new ObjectMapper();
    private static String admin;
    private static String cashier;
    private static String manager;

    private void staff() {
        if (admin == null) {
            admin = TestAccounts.tokenFor(auth, "owner", UserRole.ADMIN);
            cashier = TestAccounts.tokenFor(auth, "asha", UserRole.CASHIER);
            manager = TestAccounts.tokenFor(auth, "mani", UserRole.MANAGER);
        }
    }

    @Test
    @Order(1)
    void aShopThatHasChosenNothingWearsTheOriginalLook() throws Exception {
        staff();

        call(HttpMethod.GET, "/api/theme", cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.id").value("default"))
                .andExpect(jsonPath("$.presets.length()").value(9))
                .andExpect(jsonPath("$.active.colors.primary").value("#0284c7"));
    }

    /** Every role reads it, because every counter has to wear it. */
    @Test
    void everyoneSignedInCanReadTheTheme() throws Exception {
        staff();
        for (String token : new String[]{admin, manager, cashier}) {
            call(HttpMethod.GET, "/api/theme", token, null).andExpect(status().isOk());
            call(HttpMethod.GET, "/api/theme/revision", token, null).andExpect(status().isOk());
        }
    }

    @Test
    void nobodyUnsignedReadsIt() throws Exception {
        staff();
        call(HttpMethod.GET, "/api/theme", null, null).andExpect(status().isUnauthorized());
    }

    /** Cashiers and managers see the theme and cannot change any part of it. */
    @Test
    void onlyAnAdministratorMayApplySaveOrDeleteATheme() throws Exception {
        staff();
        String body = preset("dark");
        for (String token : new String[]{cashier, manager}) {
            call(HttpMethod.PUT, "/api/theme/active", token, body).andExpect(status().isForbidden());
            call(HttpMethod.PUT, "/api/theme/library", token, customJson("custom-nope", "#2563eb"))
                    .andExpect(status().isForbidden());
            call(HttpMethod.DELETE, "/api/theme/library/custom-nope", token, null).andExpect(status().isForbidden());
        }
    }

    /** What the administrator picks is what the cashier's counter reads next, with a new revision. */
    @Test
    void aThemeAppliedByTheAdministratorReachesEveryoneWithANewRevision() throws Exception {
        staff();
        long before = revision(cashier);

        call(HttpMethod.PUT, "/api/theme/active", admin, preset("emerald-green"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.id").value("emerald-green"));

        call(HttpMethod.GET, "/api/theme", cashier, null)
                .andExpect(jsonPath("$.active.id").value("emerald-green"))
                .andExpect(jsonPath("$.active.colors.primary").value("#047857"));
        assertThat(revision(cashier)).isGreaterThan(before);
        assertThat(revision(manager)).isEqualTo(revision(cashier));
    }

    /** A preset is the server's copy: sending the right id with other colours changes nothing. */
    @Test
    void aBuiltInThemeIsAlwaysTheServersOwnCopy() throws Exception {
        staff();
        String doctored = """
                {"id":"dark","name":"Dark","description":"",
                 "colors":%s}""".formatted(colorsJson("#ffffff", "#ffffff"));

        call(HttpMethod.PUT, "/api/theme/active", admin, doctored)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.colors.text").value("#e6edf7"));
    }

    @Test
    void aCustomThemeIsSavedAppliedAndDeleted() throws Exception {
        staff();
        String mine = customJson("custom-brand1", "#7c3aed");

        call(HttpMethod.PUT, "/api/theme/library", admin, mine)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library[?(@.id=='custom-brand1')].name").value("My brand"));
        call(HttpMethod.PUT, "/api/theme/active", admin, mine)
                .andExpect(jsonPath("$.active.id").value("custom-brand1"))
                .andExpect(jsonPath("$.active.colors.primary").value("#7c3aed"));
        call(HttpMethod.GET, "/api/theme", cashier, null)
                .andExpect(jsonPath("$.active.id").value("custom-brand1"));

        call(HttpMethod.DELETE, "/api/theme/library/custom-brand1", admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library.length()").value(0));
        // The theme in force keeps its own copy, so deleting it from the library restyles nothing.
        call(HttpMethod.GET, "/api/theme", cashier, null)
                .andExpect(jsonPath("$.active.id").value("custom-brand1"));
    }

    @Test
    void aThemeThatCannotBeReadIsRefusedInASentence() throws Exception {
        staff();
        // Pale yellow text on a white page.
        String unreadable = customJson("custom-pale", "#2563eb").replace("\"text\":\"#0f172a\"",
                "\"text\":\"#fde68a\"");

        call(HttpMethod.PUT, "/api/theme/active", admin, unreadable)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("too hard to read")));
        call(HttpMethod.PUT, "/api/theme/library", admin, unreadable).andExpect(status().isBadRequest());
    }

    @Test
    void deletingATheYouNeverSavedIsNotFound() throws Exception {
        staff();
        call(HttpMethod.DELETE, "/api/theme/library/custom-ghost", admin, null).andExpect(status().isNotFound());
    }

    private long revision(String token) throws Exception {
        String body = call(HttpMethod.GET, "/api/theme/revision", token, null).andReturn().getResponse()
                .getContentAsString();
        return json.readTree(body).get("revision").asLong();
    }

    private String preset(String id) throws Exception {
        return json.writeValueAsString(ThemePresets.byId(id).orElseThrow());
    }

    private String customJson(String id, String primary) throws Exception {
        Map<String, String> colors = new LinkedHashMap<>(ThemePresets.byId("light").orElseThrow().colors());
        colors.put("primary", primary);
        return json.writeValueAsString(new ThemeDefinition(id, "My brand", "", colors));
    }

    private String colorsJson(String text, String background) throws Exception {
        Map<String, String> colors = new LinkedHashMap<>(ThemePresets.byId("dark").orElseThrow().colors());
        colors.put("text", text);
        colors.put("background", background);
        return json.writeValueAsString(colors);
    }

    private ResultActions call(HttpMethod method, String path, String token, String body) throws Exception {
        var request = request(method, path)
                .header(LocalApiGuard.CLIENT_HEADER, "test")
                .header(HttpHeaders.HOST, "localhost");
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            request = request.contentType(APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }
}
