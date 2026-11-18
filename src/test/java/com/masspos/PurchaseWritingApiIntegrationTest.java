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
 * Writing an order the way a bill is written: things the shop does not keep a count of, money
 * handed over there and then, an order put aside half finished, and goods going back on a lorry.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + PurchaseWritingApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
class PurchaseWritingApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/purchase-writing-it";

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
            managerToken = TestAccounts.tokenFor(auth, "writing-manager", UserRole.MANAGER);
            adminToken = TestAccounts.tokenFor(auth, "writing-admin", UserRole.ADMIN);
        }
    }

    /**
     * A shop orders things that are in no catalogue: a gas regulator, an hour of a plumber's time.
     * They go on the order as a name, nothing counts them when they arrive because there is
     * nothing to count, and the vendor is owed for them all the same - which is the part that
     * would be quietly wrong if the order simply refused the line.
     */
    @Test
    void ordersSomethingTheShopDoesNotKeep() throws Exception {
        String vendorId = newVendor("WV-1", "Hardware shop");

        JsonNode order = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":null,"rawMaterialId":null,"itemName":"Gas regulator","itemCode":"GR-9",
                   "quantityMilli":2000,"unitCostPaise":145000}]}""".formatted(vendorId),
                status().isCreated());

        assertThat(order.at("/lines/0/oneOff").asBoolean()).isTrue();
        assertThat(order.at("/lines/0/name").asText()).isEqualTo("Gas regulator");
        assertThat(order.at("/lines/0/itemCode").asText()).isEqualTo("GR-9");
        assertThat(order.at("/lines/0/itemId").isNull()).as("there is no item to open").isTrue();
        assertThat(order.get("valuePaise").asLong()).isEqualTo(290_000);

        String orderId = order.get("id").asText();
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        JsonNode received = call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}",
                status().isOk());

        assertThat(received.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(received.at("/lines/0/receivedMilli").asLong()).isEqualTo(2_000);
        assertThat(received.get("owedPaise").asLong()).isEqualTo(290_000);
        // Nothing counted it, and the shop still owes for it.
        assertThat(call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk())
                .at("/vendor/outstandingPaise").asLong()).isEqualTo(290_000);
        assertThat(call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk())
                .at("/purchases/0/name").asText()).isEqualTo("Gas regulator");
    }

    /**
     * Money handed over as the order is written, which is ordinary with a vendor who wants it
     * before he loads the lorry. It is a payment to that vendor like any other: recorded at once,
     * counted only once an administrator has agreed to it.
     */
    @Test
    void paysForAnOrderAsItIsWritten() throws Exception {
        String vendorId = newVendor("WV-2", "Cash and carry");
        String productId = newProduct("WP-2", "Soap");

        JsonNode order = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":10000,"unitCostPaise":4000}],
                 "payments":[{"mode":"UPI","amountPaise":40000,"reference":"UPI-7781"}]}"""
                .formatted(vendorId, productId), status().isCreated());

        JsonNode vendor = call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk());
        assertThat(vendor.at("/vendor/waitingPaise").asLong())
                .as("recorded at once, and waiting for an administrator").isEqualTo(40_000);
        assertThat(vendor.at("/vendor/paidPaise").asLong()).isZero();
        assertThat(vendor.at("/payments/0/purchaseOrderId").asText()).isEqualTo(order.get("id").asText());
        assertThat(vendor.at("/payments/0/note").asText()).contains(order.get("number").asText());

        String paymentId = vendor.at("/payments/0/id").asText();
        call(post("/api/vendors/payments/" + paymentId + "/approve"), adminToken, null, status().isNoContent());

        assertThat(call(get("/api/purchase-orders/" + order.get("id").asText()), managerToken, null,
                status().isOk()).get("paidPaise").asLong()).isEqualTo(40_000);
    }

    /** An order put aside half written comes back exactly as it was left. */
    @Test
    void holdsAnOrderAndPicksItUpAgain() throws Exception {
        String vendorId = newVendor("WV-3", "Fruit man");

        JsonNode held = call(post("/api/purchase-orders/holds"), managerToken, """
                {"label":"Monday market","estimatedTotalPaise":64000,"order":{
                  "vendorId":"%s","vendorText":"WV-3 - Fruit man","expectedOn":"","note":"ask about crates",
                  "lines":[{"productId":null,"rawMaterialId":null,"itemName":"Bananas","itemCode":"",
                            "name":"Bananas","unit":"KGS","quantityMilli":20000,"unitCostPaise":3200}]}}"""
                .formatted(vendorId), status().isCreated());

        assertThat(held.get("label").asText()).isEqualTo("Monday market");
        assertThat(call(get("/api/purchase-orders/holds"), managerToken, null, status().isOk())).hasSize(1);

        JsonNode resumed = call(delete("/api/purchase-orders/holds/" + held.get("id").asText()), managerToken,
                null, status().isOk());

        assertThat(resumed.at("/order/note").asText()).isEqualTo("ask about crates");
        assertThat(resumed.at("/order/lines/0/name").asText()).isEqualTo("Bananas");
        assertThat(call(get("/api/purchase-orders/holds"), managerToken, null, status().isOk()))
                .as("resuming it takes it off the list, so it cannot be written twice").isEmpty();
    }

    /**
     * Goods going back on the lorry: the stock comes off, what the shop owes comes down by what
     * went back, and the order says how much of it can still go.
     */
    @Test
    void sendsGoodsBackAndStopsOwingForThem() throws Exception {
        String vendorId = newVendor("WV-4", "Dairy");
        String productId = newProduct("WP-4", "Butter 500g");
        String orderId = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":10000,"unitCostPaise":25000}]}"""
                .formatted(vendorId, productId), status().isCreated()).get("id").asText();
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}", status().isOk());
        assertThat(owed(vendorId)).isEqualTo(250_000);

        JsonNode canGoBack = call(get("/api/purchase-returns/can-go-back?orderId=" + orderId), managerToken,
                null, status().isOk());
        assertThat(canGoBack.get(0).get("receivedMilli").asLong()).isEqualTo(10_000);
        assertThat(canGoBack.get(0).get("alreadyBackMilli").asLong()).isZero();

        JsonNode sentBack = call(post("/api/purchase-returns"), managerToken, """
                {"vendorId":"%s","againstOrderId":"%s","reason":"Four were sour","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":4000,"unitCostPaise":25000}]}"""
                .formatted(vendorId, orderId, productId), status().isCreated());

        assertThat(sentBack.get("number").asText()).startsWith("PR-");
        assertThat(sentBack.get("valuePaise").asLong()).isEqualTo(100_000);
        assertThat(sentBack.get("againstOrderNumber").asText()).startsWith("PO-");
        assertThat(stockOf(productId)).as("the goods are on the lorry").isEqualTo(6_000);
        assertThat(owed(vendorId)).as("and the shop stops owing for them").isEqualTo(150_000);
        // The order says the same thing the vendor's balance does, or the two screens would argue.
        assertThat(call(get("/api/purchase-orders/" + orderId), managerToken, null, status().isOk())
                .get("owedPaise").asLong()).isEqualTo(150_000);

        // The movement reads like a delivery in reverse, with the return on it.
        JsonNode movement = call(get("/api/stock/history?productId=" + productId), managerToken, null,
                status().isOk()).get(0);
        assertThat(movement.get("type").asText()).isEqualTo("PURCHASE_RETURN");
        assertThat(movement.get("quantityDeltaMilli").asLong()).isEqualTo(-4_000);
        assertThat(movement.get("vendorName").asText()).isEqualTo("Dairy");
        assertThat(movement.get("orderNumber").asText()).isEqualTo(sentBack.get("number").asText());

        // And the order knows what is left to go back.
        assertThat(call(get("/api/purchase-returns/can-go-back?orderId=" + orderId), managerToken, null,
                status().isOk()).get(0).get("alreadyBackMilli").asLong()).isEqualTo(4_000);
    }

    /** What the shop does not have cannot go on a lorry, so a return stops at what is there. */
    @Test
    void sendsBackOnlyWhatIsActuallyThere() throws Exception {
        String vendorId = newVendor("WV-5", "Grain merchant");
        String productId = newProduct("WP-5", "Rice 10kg");
        String orderId = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":3000,"unitCostPaise":50000}]}"""
                .formatted(vendorId, productId), status().isCreated()).get("id").asText();
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}", status().isOk());

        JsonNode sentBack = call(post("/api/purchase-returns"), managerToken, """
                {"vendorId":"%s","againstOrderId":null,"reason":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":9000,"unitCostPaise":50000}]}"""
                .formatted(vendorId, productId), status().isCreated());

        assertThat(sentBack.at("/lines/0/quantityMilli").asLong())
                .as("three were there, so three went back").isEqualTo(3_000);
        assertThat(stockOf(productId)).isZero();
    }

    /** And a return with nothing on the shelf behind it is refused rather than written empty. */
    @Test
    void refusesAReturnOfWhatIsNotThere() throws Exception {
        String vendorId = newVendor("WV-6", "Paper merchant");
        String productId = newProduct("WP-6", "Carrier bags");

        assertThat(call(post("/api/purchase-returns"), managerToken, """
                {"vendorId":"%s","againstOrderId":null,"reason":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":5000,"unitCostPaise":100}]}"""
                .formatted(vendorId, productId), status().isConflict()).get("detail").asText())
                .contains("is on this till to send back");
    }

    /**
     * A shop buys from a man off a lorry. The order carries his name and nothing else, the
     * delivery carries it onto the ledger, and nobody is added to the vendor list for it.
     */
    @Test
    void ordersFromSomebodyWhoIsNotOnTheList() throws Exception {
        String productId = newProduct("WP-7", "Coconuts");

        JsonNode order = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":null,"vendorName":"The lorry man","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":30000,"unitCostPaise":1500}]}"""
                .formatted(productId), status().isCreated());

        assertThat(order.get("vendorId").isNull()).isTrue();
        assertThat(order.get("vendorName").asText()).isEqualTo("The lorry man");

        String orderId = order.get("id").asText();
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}", status().isOk());

        JsonNode movement = call(get("/api/stock/history?productId=" + productId), managerToken, null,
                status().isOk()).get(0);
        assertThat(movement.get("vendorName").asText()).isEqualTo("The lorry man");
        assertThat(stockOf(productId)).isEqualTo(30_000);
        assertThat(call(get("/api/vendors"), managerToken, null, status().isOk()).toString())
                .as("and nobody was added to the list for him").doesNotContain("The lorry man");
    }

    /**
     * Money paid to somebody who is not on the vendors list.
     *
     * <p>The shop paid him, so the shop writes it down. Refusing the payment until he had been
     * added to the list never stopped the money leaving the till, it only stopped the record of
     * it. He has no page of his own, so the order is where it shows - and the only place an
     * administrator can agree to it.
     */
    @Test
    void paysSomebodyWhoIsNotOnTheVendorsList() throws Exception {
        String productId = newProduct("WP-10", "Bananas");
        JsonNode order = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":null,"vendorName":"The lorry man","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":20000,"unitCostPaise":2000}],
                 "payments":[{"mode":"CASH","amountPaise":10000,"reference":""}]}"""
                .formatted(productId), status().isCreated());
        String orderId = order.get("id").asText();

        // And the rest of it later, from the order's own page.
        JsonNode later = call(post("/api/purchase-orders/" + orderId + "/payments"), managerToken, """
                {"amountPaise":30000,"paidOn":null,"mode":"UPI","reference":"UPI-9004","note":""}""",
                status().isCreated());
        assertThat(later.get("vendorId").isNull()).as("nobody on the list was paid").isTrue();
        assertThat(later.get("vendorName").asText()).isEqualTo("The lorry man");

        JsonNode payments = call(get("/api/purchase-orders/" + orderId + "/payments"), managerToken, null,
                status().isOk());
        assertThat(payments).hasSize(2);
        assertThat(payments.get(0).get("amountPaise").asLong()).isEqualTo(10_000);
        assertThat(payments.get(0).get("approved").asBoolean())
                .as("money waits for an administrator, vendor or no vendor").isFalse();
        assertThat(payments.get(1).get("note").asText()).contains(order.get("number").asText());

        for (JsonNode payment : payments) {
            call(post("/api/vendors/payments/" + payment.get("id").asText() + "/approve"), adminToken, null,
                    status().isNoContent());
        }
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        JsonNode received = call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}",
                status().isOk());

        assertThat(received.get("owedPaise").asLong()).isEqualTo(40_000);
        assertThat(received.get("paidPaise").asLong()).isEqualTo(40_000);
        assertThat(received.get("settled").asText()).isEqualTo("PAID");
        assertThat(call(get("/api/vendors"), managerToken, null, status().isOk()).toString())
                .as("and he is still not on the list").doesNotContain("The lorry man");
    }

    /**
     * Paying from the order is the payment the vendor's own page records: one row, one balance,
     * whichever screen it was recorded on. There is deliberately no second kind of payment.
     */
    @Test
    void payingFromTheOrderIsThePaymentTheVendorsPageRecords() throws Exception {
        String vendorId = newVendor("WV-11", "Oil mill");
        String productId = newProduct("WP-11", "Oil 5l");
        String orderId = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":2000,"unitCostPaise":60000}]}"""
                .formatted(vendorId, productId), status().isCreated()).get("id").asText();
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}", status().isOk());

        call(post("/api/purchase-orders/" + orderId + "/payments"), managerToken, """
                {"amountPaise":120000,"paidOn":null,"mode":"BANK_TRANSFER","reference":"NEFT-22","note":""}""",
                status().isCreated());

        JsonNode vendor = call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk());
        assertThat(vendor.at("/payments/0/purchaseOrderId").asText())
                .as("the vendor's page shows the very same payment").isEqualTo(orderId);
        assertThat(vendor.at("/payments/0/vendorName").asText()).isEqualTo("Oil mill");
        assertThat(vendor.at("/vendor/waitingPaise").asLong()).isEqualTo(120_000);
        assertThat(vendor.at("/vendor/outstandingPaise").asLong())
                .as("and it moves nothing until somebody agrees to it").isEqualTo(120_000);

        call(post("/api/vendors/payments/" + vendor.at("/payments/0/id").asText() + "/approve"), adminToken,
                null, status().isNoContent());

        assertThat(call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk())
                .at("/vendor/outstandingPaise").asLong()).isZero();
        assertThat(call(get("/api/purchase-orders/" + orderId), managerToken, null, status().isOk())
                .get("settled").asText()).isEqualTo("PAID");
    }

    /**
     * Changing an order is writing a new one, the way changing a bill reissues it: the old one is
     * cancelled with the new number on it, the new one carries the old number, both are in the
     * history, and what has been paid follows the order it was paid for.
     */
    @Test
    void changesAnOrderByWritingANewOneInItsPlace() throws Exception {
        String vendorId = newVendor("WV-8", "Tea merchant");
        String productId = newProduct("WP-8", "Tea 1kg");
        JsonNode first = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"Weekly","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":5000,"unitCostPaise":30000}],
                 "payments":[{"mode":"CASH","amountPaise":50000,"reference":""}]}"""
                .formatted(vendorId, productId), status().isCreated());
        String firstId = first.get("id").asText();
        String paymentId = call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk())
                .at("/payments/0/id").asText();
        call(post("/api/vendors/payments/" + paymentId + "/approve"), adminToken, null, status().isNoContent());

        JsonNode second = call(post("/api/purchase-orders/" + firstId + "/replace"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"Weekly, more of it","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":8000,"unitCostPaise":30000}]}"""
                .formatted(vendorId, productId), status().isOk());

        assertThat(second.get("number").asText()).isNotEqualTo(first.get("number").asText());
        assertThat(second.get("replacesOrderNumber").asText()).isEqualTo(first.get("number").asText());
        assertThat(second.get("status").asText())
                .as("the money changed, so it waits to be agreed to again").isEqualTo("PENDING_APPROVAL");
        assertThat(second.get("valuePaise").asLong()).isEqualTo(240_000);
        assertThat(second.get("paidPaise").asLong())
                .as("what was paid follows the order it was paid for").isEqualTo(50_000);

        JsonNode cancelled = call(get("/api/purchase-orders/" + firstId), managerToken, null, status().isOk());
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("decision").asText()).contains("Edited, replaced by")
                .contains(second.get("number").asText());
        assertThat(cancelled.get("paidPaise").asLong()).as("and no longer counts on the old one").isZero();

        // Both versions are on the story, oldest first, whichever end it is opened from.
        JsonNode story = call(get("/api/purchase-orders/" + firstId + "/history"), managerToken, null,
                status().isOk());
        assertThat(story.get("versions")).hasSize(2);
        assertThat(story.at("/versions/0/number").asText()).isEqualTo(first.get("number").asText());
        assertThat(story.at("/versions/1/number").asText()).isEqualTo(second.get("number").asText());
    }

    /** Once the goods are in the shop the counts have moved, so there is nothing left to change. */
    @Test
    void refusesToChangeAnOrderThatHasAlreadyComeIn() throws Exception {
        String vendorId = newVendor("WV-9", "Spice merchant");
        String productId = newProduct("WP-9", "Pepper 1kg");
        String orderId = call(post("/api/purchase-orders"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":2000,"unitCostPaise":80000}]}"""
                .formatted(vendorId, productId), status().isCreated()).get("id").asText();
        call(post("/api/purchase-orders/" + orderId + "/approve"), adminToken, null, status().isOk());
        call(post("/api/purchase-orders/" + orderId + "/receive"), managerToken, "{}", status().isOk());

        assertThat(call(post("/api/purchase-orders/" + orderId + "/replace"), managerToken, """
                {"vendorId":"%s","expectedOn":null,"note":"","lines":[
                  {"productId":"%s","rawMaterialId":null,"quantityMilli":1000,"unitCostPaise":80000}]}"""
                .formatted(vendorId, productId), status().isConflict()).get("detail").asText())
                .contains("already come in");
    }

    private long owed(String vendorId) throws Exception {
        return call(get("/api/vendors/" + vendorId), managerToken, null, status().isOk())
                .at("/vendor/outstandingPaise").asLong();
    }

    private long stockOf(String productId) throws Exception {
        return call(get("/api/products/" + productId), managerToken, null, status().isOk())
                .get("stockMilli").asLong();
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
