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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Changing a bill after it was taken: editing today's bill, and returns with or without a bill. */
@SpringBootTest(properties = {
        "pos.data-dir=" + ReturnsAndEditsApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.gstin=27AAPFU0939F1ZV",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
class ReturnsAndEditsApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/returns-it";
    private static final Path PRINTER_OUT = Path.of(DATA_DIR, "printer-out");

    private static String cashierToken;
    private static String managerToken;

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
        if (cashierToken == null) {
            cashierToken = TestAccounts.tokenFor(auth, "ret-cashier", UserRole.CASHIER);
            managerToken = TestAccounts.tokenFor(auth, "ret-manager", UserRole.MANAGER);
        }
    }

    // ---- Editing a bill ---------------------------------------------------------------------------

    @Test
    void editingTodaysBillReplacesItRestocksAndRefundsTheDifference() throws Exception {
        String productId = newProductWithStock(10_000);
        JsonNode original = sell(productId, 3_000, 30_000, "CASH");
        assertThat(stockOf(productId)).isEqualTo(7_000);

        JsonNode edited = call(post("/api/invoices/" + original.get("id").asText() + "/replace"), managerToken, """
                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],"payments":[],"print":true}
                """.formatted(productId), status().isOk());

        JsonNode replacement = edited.get("invoice");
        assertThat(edited.get("replacedInvoiceNumber").asText()).isEqualTo(original.get("invoiceNumber").asText());
        assertThat(edited.get("refundPaise").asLong()).isEqualTo(20_000);
        assertThat(edited.get("collectedPaise").asLong()).isZero();
        assertThat(replacement.get("invoiceNumber").asText()).isNotEqualTo(original.get("invoiceNumber").asText());
        assertThat(replacement.get("replacesInvoiceNumber").asText()).isEqualTo(original.get("invoiceNumber").asText());
        assertThat(replacement.get("grandTotalPaise").asLong()).isEqualTo(10_000);
        assertThat(replacement.get("payments")).hasSize(1);
        assertThat(replacement.get("payments").get(0).get("mode").asText()).isEqualTo("CASH");
        assertThat(replacement.get("payments").get(0).get("amountPaise").asLong()).isEqualTo(10_000);
        // Two back on the shelf: all three came back with the old bill, one went out on the new one.
        assertThat(stockOf(productId)).isEqualTo(9_000);

        JsonNode old = call(get("/api/invoices/" + original.get("id").asText()), cashierToken, null, status().isOk());
        assertThat(old.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(old.get("cancelReason").asText()).contains(replacement.get("invoiceNumber").asText());
        assertThat(Files.readString(latestJob())).contains("Replaces bill: " + original.get("invoiceNumber").asText());
    }

    @Test
    void editingABillUpwardsCollectsOnlyTheDifference() throws Exception {
        String productId = newProductWithStock(10_000);
        JsonNode original = sell(productId, 1_000, 10_000, "CASH");
        String replace = "/api/invoices/" + original.get("id").asText() + "/replace";

        JsonNode unpaid = call(post(replace), managerToken, """
                {"lines":[{"productId":"%s","quantityMilli":3000,"discountPaise":0}],"payments":[],"print":false}
                """.formatted(productId), status().isBadRequest());
        assertThat(unpaid.get("detail").asText()).contains("Collect 200.00 more");

        JsonNode edited = call(post(replace), managerToken, """
                {"lines":[{"productId":"%s","quantityMilli":3000,"discountPaise":0}],
                 "payments":[{"mode":"UPI","amountPaise":20000,"tenderedPaise":20000,"reference":"UPI-9"}],
                 "print":false}
                """.formatted(productId), status().isOk());

        assertThat(edited.get("collectedPaise").asLong()).isEqualTo(20_000);
        assertThat(edited.get("refundPaise").asLong()).isZero();
        JsonNode payments = edited.get("invoice").get("payments");
        assertThat(payments).hasSize(2);
        assertThat(payments.findValuesAsText("mode")).containsExactly("CASH", "UPI");
        assertThat(stockOf(productId)).isEqualTo(7_000);
    }

    @Test
    void onlyAManagerEditsAndNeverACancelledBill() throws Exception {
        String productId = newProductWithStock(10_000);
        JsonNode original = sell(productId, 1_000, 10_000, "CASH");
        String replace = "/api/invoices/" + original.get("id").asText() + "/replace";
        String body = """
                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],"payments":[],"print":false}
                """.formatted(productId);

        call(post(replace), cashierToken, body, status().isForbidden());
        call(post(replace), managerToken, body, status().isOk());
        // The original is now cancelled, so a second edit of it is refused.
        JsonNode again = call(post(replace), managerToken, body, status().isBadRequest());
        assertThat(again.get("detail").asText()).contains("cancelled");
    }

    // ---- Returns ------------------------------------------------------------------------------------

    @Test
    void aReturnAgainstABillRestocksRefundsAndCannotExceedWhatWasSold() throws Exception {
        String productId = newProductWithStock(10_000);
        JsonNode bill = sell(productId, 3_000, 30_000, "CASH");
        String billId = bill.get("id").asText();

        JsonNode returnable = call(get("/api/invoices/" + billId + "/returnable"), cashierToken, null, status().isOk());
        assertThat(returnable.get("lines").get(0).get("soldMilli").asLong()).isEqualTo(3_000);
        assertThat(returnable.get("lines").get(0).get("returnedMilli").asLong()).isZero();

        JsonNode quoted = call(post("/api/returns/quote"), cashierToken, returnOf(billId, productId, 2_000, null),
                status().isOk());
        assertThat(quoted.get("grandTotalPaise").asLong()).isEqualTo(20_000);

        JsonNode note = call(post("/api/returns"), managerToken, returnOf(billId, productId, 2_000, 20_000),
                status().isCreated()).get("creditNote");
        assertThat(note.get("creditNoteNumber").asText()).matches("T1-\\d{4}-R\\d{4}");
        assertThat(note.get("originalInvoiceNumber").asText()).isEqualTo(bill.get("invoiceNumber").asText());
        assertThat(note.get("grandTotalPaise").asLong()).isEqualTo(20_000);
        assertThat(note.get("taxableValuePaise").asLong()).isEqualTo(16_949);
        assertThat(stockOf(productId)).isEqualTo(9_000);
        assertThat(Files.readString(latestJob()))
                .contains("CREDIT NOTE", "Against bill: " + bill.get("invoiceNumber").asText(), "REFUND Rs.", "200.00");

        JsonNode tooMany = call(post("/api/returns"), managerToken, returnOf(billId, productId, 2_000, 20_000),
                status().isBadRequest());
        assertThat(tooMany.get("detail").asText()).contains("only 1 left to return");

        // The last one takes whatever is left of the bill's own figures, so the returns add up to the bill.
        JsonNode last = call(post("/api/returns"), managerToken, returnOf(billId, productId, 1_000, 10_000),
                status().isCreated()).get("creditNote");
        assertThat(last.get("taxableValuePaise").asLong()).isEqualTo(25_424 - 16_949);
        assertThat(stockOf(productId)).isEqualTo(10_000);
    }

    @Test
    void aBillWithReturnsAgainstItCanNoLongerBeEditedOrCancelled() throws Exception {
        String productId = newProductWithStock(10_000);
        JsonNode bill = sell(productId, 2_000, 20_000, "CASH");
        String billId = bill.get("id").asText();
        call(post("/api/returns"), managerToken, returnOf(billId, productId, 1_000, 10_000), status().isCreated());

        JsonNode edit = call(post("/api/invoices/" + billId + "/replace"), managerToken, """
                {"lines":[{"productId":"%s","quantityMilli":1000,"discountPaise":0}],"payments":[],"print":false}
                """.formatted(productId), status().isBadRequest());
        assertThat(edit.get("detail").asText()).contains("returns against it");
        JsonNode cancel = call(post("/api/invoices/" + billId + "/cancel"), managerToken,
                "{\"reason\":\"Mistake\"}", status().isBadRequest());
        assertThat(cancel.get("detail").asText()).contains("returns against it");
        // One came back on the return, and nothing more.
        assertThat(stockOf(productId)).isEqualTo(9_000);
    }

    @Test
    void aReturnWithoutABillIsPricedLikeASaleAndCheckedAgainstTheRefund() throws Exception {
        String productId = newProductWithStock(1_000);

        JsonNode wrongRefund = call(post("/api/returns"), managerToken, returnOf(null, productId, 1_000, 5_000),
                status().isBadRequest());
        assertThat(wrongRefund.get("detail").asText()).contains("Refunds add up to 50.00 but the return is 100.00");

        call(post("/api/returns"), cashierToken, returnOf(null, productId, 1_000, 10_000), status().isForbidden());

        JsonNode note = call(post("/api/returns"), managerToken, returnOf(null, productId, 1_000, 10_000),
                status().isCreated()).get("creditNote");
        assertThat(note.get("grandTotalPaise").asLong()).isEqualTo(10_000);
        assertThat(note.get("taxableValuePaise").asLong()).isEqualTo(8_475);
        assertThat(note.get("originalInvoiceNumber").isNull()).isTrue();
        assertThat(stockOf(productId)).isEqualTo(2_000);
    }

    @Test
    void theDaysTakingsAreNetOfReturns() throws Exception {
        String productId = newProductWithStock(10_000);
        JsonNode before = call(get("/api/reports/day"), managerToken, null, status().isOk());

        call(post("/api/returns"), managerToken, returnOf(null, productId, 1_000, 10_000), status().isCreated());

        JsonNode after = call(get("/api/reports/day"), managerToken, null, status().isOk());
        assertThat(after.get("returns").asLong()).isEqualTo(before.get("returns").asLong() + 1);
        assertThat(after.get("returnTotals").get("grandTotalPaise").asLong())
                .isEqualTo(before.get("returnTotals").get("grandTotalPaise").asLong() + 10_000);
        assertThat(after.get("totals").get("grandTotalPaise").asLong())
                .isEqualTo(before.get("totals").get("grandTotalPaise").asLong() - 10_000);
        assertThat(cashIn(after)).isEqualTo(cashIn(before) - 10_000);

        JsonNode returns = call(get("/api/returns"), cashierToken, null, status().isOk());
        assertThat(returns.findValuesAsText("creditNoteNumber")).isNotEmpty();
    }

    @Test
    void everyVersionOfAnEditedBillIsListedOldestFirst() throws Exception {
        String productId = newProductWithStock(10_000);
        JsonNode first = sell(productId, 3_000, 30_000, "CASH");
        String body = """
                {"lines":[{"productId":"%s","quantityMilli":%d,"discountPaise":0}],"payments":[],"print":false}
                """;
        JsonNode second = call(post("/api/invoices/" + first.get("id").asText() + "/replace"), managerToken,
                body.formatted(productId, 2_000), status().isOk()).get("invoice");
        JsonNode third = call(post("/api/invoices/" + second.get("id").asText() + "/replace"), managerToken,
                body.formatted(productId, 1_000), status().isOk()).get("invoice");
        java.util.List<String> chain = java.util.List.of(first.get("invoiceNumber").asText(),
                second.get("invoiceNumber").asText(), third.get("invoiceNumber").asText());

        // Opened from any version, the whole chain comes back in the order the bills were made.
        for (JsonNode opened : java.util.List.of(first, second, third)) {
            JsonNode history = call(get("/api/invoices/" + opened.get("id").asText() + "/history"), cashierToken,
                    null, status().isOk());
            assertThat(history.findValuesAsText("invoiceNumber")).containsExactlyElementsOf(chain);
        }

        JsonNode alone = sell(productId, 1_000, 10_000, "CASH");
        assertThat(call(get("/api/invoices/" + alone.get("id").asText() + "/history"), cashierToken, null,
                status().isOk()).findValuesAsText("invoiceNumber")).containsExactly(alone.get("invoiceNumber").asText());
    }

    @Test
    void aReturnIsFoundByItsNumberOrJustItsSerial() throws Exception {
        String productId = newProductWithStock(1_000);
        JsonNode note = call(post("/api/returns"), managerToken, returnOf(null, productId, 1_000, 10_000),
                status().isCreated()).get("creditNote");
        String number = note.get("creditNoteNumber").asText();
        String serial = "R" + Long.parseLong(number.substring(number.lastIndexOf('R') + 1));

        assertThat(call(get("/api/returns/lookup?number=" + number), cashierToken, null, status().isOk())
                .get("id").asText()).isEqualTo(note.get("id").asText());
        assertThat(call(get("/api/returns/lookup?number=" + serial.toLowerCase()), cashierToken, null, status().isOk())
                .get("id").asText()).isEqualTo(note.get("id").asText());
        call(get("/api/returns/lookup?number=R9999"), cashierToken, null, status().isNotFound());
    }

    @Test
    void aBillIsFoundByItsNumberOrJustItsSerial() throws Exception {
        String productId = newProductWithStock(1_000);
        JsonNode bill = sell(productId, 1_000, 10_000, "CASH");
        String number = bill.get("invoiceNumber").asText();
        String serial = String.valueOf(Long.parseLong(number.substring(number.lastIndexOf('-') + 1)));

        assertThat(call(get("/api/invoices/lookup?number=" + number), cashierToken, null, status().isOk())
                .get("id").asText()).isEqualTo(bill.get("id").asText());
        assertThat(call(get("/api/invoices/lookup?number=" + serial), cashierToken, null, status().isOk())
                .get("id").asText()).isEqualTo(bill.get("id").asText());
        call(get("/api/invoices/lookup?number=T1-2627-99999"), cashierToken, null, status().isNotFound());
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private static String returnOf(String invoiceId, String productId, long quantityMilli, Integer refundPaise) {
        String line = invoiceId == null
                ? "{\"productId\":\"%s\",\"quantityMilli\":%d,\"discountPaise\":0}".formatted(productId, quantityMilli)
                : "{\"productId\":\"%s\",\"originalLineNo\":1,\"quantityMilli\":%d,\"discountPaise\":0}"
                        .formatted(productId, quantityMilli);
        String refunds = refundPaise == null ? "[]"
                : "[{\"mode\":\"CASH\",\"amountPaise\":%d}]".formatted(refundPaise);
        return """
                {"againstInvoiceId":%s,"lines":[%s],"refunds":%s,"reason":"Customer returned it","print":true}
                """.formatted(invoiceId == null ? "null" : "\"" + invoiceId + "\"", line, refunds);
    }

    private static long cashIn(JsonNode day) {
        for (JsonNode row : day.get("payments")) {
            if (row.get("mode").asText().equals("CASH")) {
                return row.get("amountPaise").asLong();
            }
        }
        return 0;
    }

    /** Sells at Rs 100.00 a piece, inclusive of 18% GST. */
    private JsonNode sell(String productId, long quantityMilli, long totalPaise, String mode) throws Exception {
        return call(post("/api/sales"), cashierToken, """
                {"lines":[{"productId":"%s","quantityMilli":%d,"discountPaise":0}],
                 "payments":[{"mode":"%s","amountPaise":%d,"tenderedPaise":%d}],"print":false}
                """.formatted(productId, quantityMilli, mode, totalPaise, totalPaise), status().isCreated())
                .get("invoice");
    }

    private String newProductWithStock(long quantityMilli) throws Exception {
        String sku = "RET-" + TestFixtures.unique();
        String productId = call(post("/api/products"), managerToken, """
                {"sku":"%s","name":"Shirt %s","hsnCode":"6205","unit":"NOS",
                 "sellingPricePaise":10000,"mrpPaise":10000,"taxInclusive":true,"gstRateBp":1800,"cessRateBp":0}
                """.formatted(sku, sku), status().isCreated()).get("id").asText();
        call(post("/api/stock/receipts"), managerToken,
                "{\"productId\":\"%s\",\"quantityMilli\":%d,\"note\":\"Opening\"}".formatted(productId, quantityMilli),
                status().isCreated());
        return productId;
    }

    private long stockOf(String productId) throws Exception {
        return call(get("/api/products/" + productId), cashierToken, null, status().isOk()).get("stockMilli").asLong();
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

    private static Path latestJob() throws IOException {
        try (Stream<Path> files = Files.list(PRINTER_OUT)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        }
    }
}
