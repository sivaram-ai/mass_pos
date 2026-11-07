package com.masspos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.auth.AuthService;
import com.masspos.billing.InvoiceNumbers;
import com.masspos.common.IndiaTime;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The number a shop reads off a bill starts again at 1 every morning, and the day it was taken on
 * is in the number so that two mornings never produce the same one.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + DailyBillNumberApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
@Import(TestLicensing.Config.class)
// The day's counter is one per till, so the test that asserts the first bill of the day is 1 has
// to be the one that takes it.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DailyBillNumberApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/daily-number-it";

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
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void staffOnShift() {
        if (cashierToken == null) {
            cashierToken = TestAccounts.tokenFor(auth, "day-cashier", UserRole.CASHIER);
            managerToken = TestAccounts.tokenFor(auth, "day-manager", UserRole.MANAGER);
        }
    }

    /**
     * Yesterday's till took fifty bills. This morning's first bill is number 1, not fifty-one, and
     * yesterday's count is left exactly as it was - it is what yesterday's bills were numbered from.
     */
    @Test
    @Order(1)
    void theFirstBillAfterMidnightIsNumberOneAgain() throws Exception {
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        LocalDate yesterday = today.minusDays(1);
        jdbc.update("INSERT INTO invoice_daily_sequence (id, version, created_at, updated_at, terminal_code,"
                        + " business_date, last_issued) VALUES (?, 0, 1, 1, 'T1', ?, 50)",
                UUID.randomUUID().toString(), yesterday.toString());

        String productId = newProductWithStock("DAY-1", "Sugar 1kg");

        assertThat(sell(productId)).isEqualTo(InvoiceNumbers.format("T1", today, 1));
        assertThat(sell(productId)).isEqualTo(InvoiceNumbers.format("T1", today, 2));

        Long yesterdaysCount = jdbc.queryForObject(
                "SELECT last_issued FROM invoice_daily_sequence WHERE business_date = ?", Long.class,
                yesterday.toString());
        assertThat(yesterdaysCount).isEqualTo(50);
    }

    /** The books keep their own count, which runs on across the year and never restarts. */
    @Test
    @Order(2)
    void theYearsCountCarriesOnBehindTheDaysCount() throws Exception {
        String productId = newProductWithStock("DAY-2", "Tea 250g");
        sell(productId);

        Long yearsCount = jdbc.queryForObject(
                "SELECT last_issued FROM invoice_sequence WHERE terminal_code = 'T1'", Long.class);
        Long daysCount = jdbc.queryForObject(
                "SELECT last_issued FROM invoice_daily_sequence WHERE business_date = ?", Long.class,
                LocalDate.now(IndiaTime.ZONE).toString());

        // Every bill this class has taken is in the year's count; only today's are in the day's.
        assertThat(yearsCount).isGreaterThanOrEqualTo(daysCount);
        assertThat(daysCount).isPositive();
    }

    /** A cashier types the running number alone, and means a bill taken today. */
    @Test
    @Order(3)
    void aBareNumberFindsTodaysBill() throws Exception {
        String productId = newProductWithStock("DAY-3", "Rice 5kg");
        String number = sell(productId);
        String serial = InvoiceNumbers.serialOf(number);

        JsonNode found = call(get("/api/invoices/lookup?number=" + Long.parseLong(serial)), cashierToken, null,
                status().isOk());
        assertThat(found.get("invoiceNumber").asText()).isEqualTo(number);
    }

    /** Returns run in their own series for the day, also starting at 1 and also marked R. */
    @Test
    @Order(4)
    void aReturnIsNumberedForTheDayToo() throws Exception {
        String productId = newProductWithStock("DAY-4", "Soap bar");
        String billNumber = sell(productId);
        String billId = call(get("/api/invoices/lookup?number=" + billNumber), managerToken, null, status().isOk())
                .get("id").asText();

        JsonNode note = call(post("/api/returns"), managerToken, """
                {"againstInvoiceId":"%s",
                 "lines":[{"productId":"%s","originalLineNo":1,"quantityMilli":1000,"discountPaise":0}],
                 "refunds":[{"mode":"CASH","amountPaise":10000}],"reason":"Damaged","print":false}
                """.formatted(billId, productId), status().isCreated()).get("creditNote");

        assertThat(note.get("creditNoteNumber").asText())
                .isEqualTo(InvoiceNumbers.formatCreditNote("T1", LocalDate.now(IndiaTime.ZONE), 1));
    }

    private String newProductWithStock(String sku, String name) throws Exception {
        String id = call(post("/api/products"), managerToken, """
                {"sku":"%s","name":"%s","hsnCode":"","unit":"NOS","sellingPricePaise":10000,"mrpPaise":0,
                 "taxInclusive":true,"gstRateBp":0,"cessRateBp":0,"billWithoutStock":true}
                """.formatted(sku, name), status().isCreated()).get("id").asText();
        call(post("/api/stock/receipts"), managerToken, """
                {"productId":"%s","quantityMilli":50000,"note":"opening"}
                """.formatted(id), status().isCreated());
        return id;
    }

    /** @return the number the bill was issued under */
    private String sell(String productId) throws Exception {
        return call(post("/api/sales"), cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],
                 "payments":[{"mode":"CASH","amountPaise":10000,"tenderedPaise":10000}],"print":false}
                """.formatted(productId), status().isCreated())
                .get("invoice").get("invoiceNumber").asText();
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
