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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What sold, and the three ways a manager gets at it: the panel on screen, the whole list behind
 * View All, and the sheet or the paper it goes out on.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + SoldItemsReportApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
@Import(TestLicensing.Config.class)
class SoldItemsReportApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/sold-items-it";
    /** More than the ten the panel shows, so "all" and "best sellers" are visibly different. */
    private static final int ITEMS = 14;

    private static String cashierToken;
    private static String managerToken;
    private static boolean sold;

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

    /**
     * Fourteen items, each sold a different number of times: item 1 goes out once, item 14
     * fourteen times. So the order most-sold-first is known, and so is which ten are the head.
     */
    @BeforeEach
    void aDaysTrading() throws Exception {
        if (cashierToken == null) {
            cashierToken = TestAccounts.tokenFor(auth, "sold-cashier", UserRole.CASHIER);
            managerToken = TestAccounts.tokenFor(auth, "sold-manager", UserRole.MANAGER);
        }
        if (sold) {
            return;
        }
        for (int n = 1; n <= ITEMS; n++) {
            String id = call(post("/api/products"), managerToken, """
                    {"sku":"SOLD-%02d","name":"Item %02d","hsnCode":"","unit":"NOS","sellingPricePaise":10000,
                     "mrpPaise":0,"taxInclusive":true,"gstRateBp":0,"cessRateBp":0,"billWithoutStock":true}
                    """.formatted(n, n), status().isCreated()).get("id").asText();
            long quantityMilli = n * 1_000L;
            call(post("/api/sales"), cashierToken, """
                    {"lines":[{"productId":"%s","quantityMilli":%d,"discountPaise":0}],
                     "payments":[{"mode":"CASH","amountPaise":%d,"tenderedPaise":%d}],"print":false}
                    """.formatted(id, quantityMilli, n * 10_000, n * 10_000), status().isCreated());
        }
        sold = true;
    }

    @Test
    void everyItemThatSoldComesBackMostSoldFirst() throws Exception {
        List<JsonNode> items = soldItems();

        assertThat(items).hasSize(ITEMS);
        // Item 14 went out fourteen times, item 1 once.
        assertThat(items.get(0).get("name").asText()).isEqualTo("Item %02d".formatted(ITEMS));
        assertThat(items.get(ITEMS - 1).get("name").asText()).isEqualTo("Item 01");

        List<Long> quantities = items.stream().map(row -> row.get("quantityMilli").asLong()).toList();
        assertThat(quantities).isSortedAccordingTo(Comparator.reverseOrder());
    }

    /**
     * The panel shows the first ten of this list rather than a list of its own, so what View All
     * shows always contains what the panel shows - which is the whole point of one list.
     */
    @Test
    void theBestSellersArePlainlyTheHeadOfTheList() throws Exception {
        List<JsonNode> items = soldItems();

        List<String> headOfAll = items.subList(0, 10).stream().map(row -> row.get("name").asText()).toList();
        assertThat(headOfAll).containsExactly("Item 14", "Item 13", "Item 12", "Item 11", "Item 10",
                "Item 09", "Item 08", "Item 07", "Item 06", "Item 05");
        assertThat(items).hasSizeGreaterThan(headOfAll.size());
    }

    /** The sheet is kept and read later, so it carries every item, not the ten on screen. */
    @Test
    void theExportedSheetCarriesEveryItemNotJustTheTopTen() throws Exception {
        String csv = mvc.perform(as(get("/api/reports/export?report=day"), managerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("Items sold", "Item,Quantity,Bill lines,Amount");
        for (int n = 1; n <= ITEMS; n++) {
            assertThat(csv).contains("Item %02d".formatted(n));
        }
    }

    @Test
    void printingAllItemsPutsEveryOneOnThePaper() throws Exception {
        call(post("/api/reports/print?items=ALL"), managerToken, null, status().isNoContent());

        String printed = Files.readString(latestPrinterJob());
        assertThat(printed).contains("DAY'S TAKINGS", "MASS MART", "ALL ITEMS SOLD", "TOTAL");
        for (int n = 1; n <= ITEMS; n++) {
            assertThat(printed).contains("Item %02d".formatted(n));
        }
    }

    @Test
    void printingBestSellersOnlyStopsAtTenAndSaysSo() throws Exception {
        call(post("/api/reports/print?items=BEST_SELLERS"), managerToken, null, status().isNoContent());

        String printed = Files.readString(latestPrinterJob());
        assertThat(printed).contains("BEST SELLERS", "Item 14", "Item 05")
                .contains("(top 10 of %d items)".formatted(ITEMS))
                // The four that did not make the cut are off the paper.
                .doesNotContain("Item 04", "Item 03", "Item 02", "Item 01");
    }

    @Test
    void onlyTheBooksMayPrintTheTakings() throws Exception {
        mvc.perform(as(post("/api/reports/print"), cashierToken).header(LocalApiGuard.CLIENT_HEADER, "test"))
                .andExpect(status().isForbidden());
    }

    private List<JsonNode> soldItems() throws Exception {
        JsonNode day = call(get("/api/reports/day"), managerToken, null, status().isOk());
        List<JsonNode> items = new ArrayList<>();
        day.get("soldItems").forEach(items::add);
        return items;
    }

    private static Path latestPrinterJob() throws Exception {
        try (Stream<Path> files = Files.list(Path.of(DATA_DIR, "printer-out"))) {
            return files.filter(file -> file.getFileName().toString().endsWith(".txt"))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        }
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String token, String body, ResultMatcher expected)
            throws Exception {
        as(request, token).header(LocalApiGuard.CLIENT_HEADER, "test");
        if (body != null) {
            request.contentType(APPLICATION_JSON).content(body);
        }
        String response = mvc.perform(request).andExpect(expected).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? json.nullNode() : json.readTree(response);
    }
}
