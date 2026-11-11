package com.masspos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.auth.AuthService;
import com.masspos.terminal.TerminalRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Counters billing against a server: who is let in, who is turned away, and whose number a bill
 * carries once they are. This shop is set up for two counters besides the server itself.
 *
 * <p>Every refusal here is read by somebody standing at a till with a queue in front of them, so
 * each one is checked for the sentence that tells them what to do, not only for the status code.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + ClientsApiIntegrationTest.DATA_DIR,
        "pos.clients.max=2",
        "pos.printer.type=FILE",
        "pos.terminal.code=T1",
        "pos.company.legal-name=Mass Retail Private Limited",
        "pos.company.trade-name=MASS MART",
        "pos.company.address[0]=12 MG Road, Camp"})
@AutoConfigureMockMvc
@Import(TestLicensing.Config.class)
class ClientsApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/clients-it";

    static {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
        TestLicensing.install(DATA_DIR);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AuthService auth;
    @Autowired
    TerminalRepository terminals;

    private final ObjectMapper json = new ObjectMapper();
    private static boolean staffReady;

    /**
     * An empty shop before each test. The places are shop-wide and a counter holds its one for
     * hours, which is the behaviour being tested - so tests that each need a free place have to
     * stop inheriting the counters the last one left behind.
     */
    @BeforeEach
    void anEmptyShopWithOneCashier() {
        if (!staffReady) {
            TestAccounts.tokenFor(auth, "asha", UserRole.CASHIER);
            staffReady = true;
        }
        terminals.deleteAll(terminals.findAll().stream()
                .filter(terminal -> !terminal.getCode().equals("T1"))
                .toList());
    }

    /** A counter is admitted on sign-in, and what it does afterwards is tagged as its own. */
    @Test
    void aCounterOnTheLanBillsUnderItsOwnNumber() throws Exception {
        assertThat(counterOf(signIn("192.168.1.51", "T2").andExpect(status().isOk()))).isEqualTo("T2");
    }

    /** The server's own browser needs no permission and bills as the server. */
    @Test
    void theBrowserOnTheServerItselfBillsAsTheServer() throws Exception {
        assertThat(counterOf(mvc.perform(login()).andExpect(status().isOk()))).isEqualTo("T1");
    }

    /** A counter that has signed in once keeps its place and does not take a second one. */
    @Test
    void thesameCounterComingBackDoesNotSpendAnotherPlace() throws Exception {
        signIn("192.168.1.52", "TD").andExpect(status().isOk());
        signIn("192.168.1.52", "TD").andExpect(status().isOk());
        signIn("192.168.1.53", "TE").andExpect(status().isOk());
    }

    /**
     * Past the number the shop set up, the next machine is told so in a sentence naming the
     * counters already in, rather than being left reading "forbidden".
     */
    @Test
    void theMachineAfterTheLastPlaceIsTurnedAwayAndTold() throws Exception {
        signIn("192.168.1.61", "TA").andExpect(status().isOk());
        signIn("192.168.1.62", "TB").andExpect(status().isOk());

        signIn("192.168.1.63", "TC")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("set up for 2 counters")))
                .andExpect(jsonPath("$.detail").value(containsString("TC")));
    }

    /** Two machines on one series would reuse bill numbers, so the server's own code is refused. */
    @Test
    void aCounterCannotClaimToBeTheServer() throws Exception {
        signIn("192.168.1.71", "T1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("is the server itself")));
    }

    /** A machine nobody has set up yet is told what to go and do. */
    @Test
    void aMachineWithNoCounterNumberIsToldToSetOne() throws Exception {
        signIn("192.168.1.81", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("has not been given a counter number")));
    }

    /** Nonsense in the counter box is refused before it can reach a bill number. */
    @Test
    void aCounterNumberThatCouldNotGoOnABillIsRefused() throws Exception {
        signIn("192.168.1.91", "COUNTER-TWO")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("not a usable counter number")));
    }

    /**
     * The screen a counter is looking at is the counter's own till. It used to be the server's,
     * because the settings view answered from the machine holding the database: Counter 2 was shown
     * "Counter 1 (T1)" in its header, and "this machine" was marked against the server's row in the
     * list of tills - so a shop with three machines was told three times that it was the first one.
     */
    @Test
    void aCounterIsShownItsOwnTillAndNotTheServers() throws Exception {
        String token = tokenOf(signIn("192.168.1.55", "T2", "Counter 2").andExpect(status().isOk()));

        settingsSeenBy(token, "T2")
                .andExpect(jsonPath("$.thisTerminal.code").value("T2"))
                .andExpect(jsonPath("$.thisTerminal.name").value("Counter 2"))
                .andExpect(jsonPath("$.thisTerminal.type").value("COUNTER"))
                .andExpect(jsonPath("$.terminals[?(@.code == 'T2')].name").value("Counter 2"));
    }

    /** The browser on the server itself is still shown the server, named from its own file. */
    @Test
    void theBrowserOnTheServerIsStillShownTheServersTill() throws Exception {
        String token = tokenOf(mvc.perform(login()).andExpect(status().isOk()));

        settingsSeenBy(token, null).andExpect(jsonPath("$.thisTerminal.code").value("T1"));
    }

    /**
     * A machine renamed in its own settings window says so at the next sign-in, and the name
     * follows it onto every screen. Keeping the first name it ever gave would mean a shop could
     * never correct a typo without somebody editing the database.
     */
    @Test
    void takesTheNameACounterWasGivenEachTimeItSignsIn() throws Exception {
        signIn("192.168.1.56", "TF", "Coutner 2").andExpect(status().isOk());

        String token = tokenOf(signIn("192.168.1.56", "TF", "Counter 2").andExpect(status().isOk()));

        settingsSeenBy(token, "TF").andExpect(jsonPath("$.thisTerminal.name").value("Counter 2"));
    }

    /**
     * The shop's list of tills says which ones somebody is standing at and who that is - what a
     * manager in the back office wants to know before ringing a counter or closing the shop.
     *
     * <p>Both halves have to hold. A machine whose screen has gone - switched off, or carried away
     * at the end of the day without anybody signing out - must not keep reading as billing; and
     * signing out must empty the till at once, however recently that machine spoke.
     */
    @Test
    void aTillSaysWhoIsBillingOnItWhileSomebodyIsThere() throws Exception {
        String token = tokenOf(signIn("192.168.1.57", "TG", "Counter 7").andExpect(status().isOk()));

        // Signed in, so it holds a place and the shop knows who is on it - but its screen has said
        // nothing yet, so it is not claimed to be billing.
        JsonNode quiet = tillSeenBy(token, "TG");
        assertThat(quiet.get("inUse").asBoolean()).isFalse();
        assertThat(quiet.get("signedIn").asBoolean()).isTrue();
        assertThat(quiet.get("staff").asText()).isEqualTo("Test CASHIER");

        stillHere(token, "TG").andExpect(status().isNoContent());

        JsonNode billing = tillSeenBy(token, "TG");
        assertThat(billing.get("inUse").asBoolean()).isTrue();
        assertThat(billing.get("staff").asText()).isEqualTo("Test CASHIER");
        settingsSeenBy(token, "TG").andExpect(jsonPath("$.thisTerminal.inUse").value(true));

        mvc.perform(post("/api/auth/logout")
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .header(HttpHeaders.HOST, "localhost")
                        .header(LocalApiGuard.COUNTER_HEADER, "TG")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        // Somebody else's screen asking now: the counter spoke a moment ago, but the person on it
        // has gone, so it is billing nothing and holds no place.
        String office = tokenOf(mvc.perform(login()).andExpect(status().isOk()));
        JsonNode emptied = tillSeenBy(office, null, "TG");
        assertThat(emptied.get("inUse").asBoolean()).isFalse();
        assertThat(emptied.get("signedIn").asBoolean()).isFalse();
        assertThat(emptied.get("staff").isNull()).isTrue();
    }

    /**
     * A place belongs to whoever is signed in, not to whoever once was. Pressing Log out hands it
     * straight back, which is what a shop expects when it takes a machine off a counter: the next
     * one should be able to sign in without waiting hours for an idle timer.
     */
    @Test
    void loggingOutGivesTheCountersPlaceBackAtOnce() throws Exception {
        String leaving = tokenOf(signIn("192.168.1.101", "TH").andExpect(status().isOk()));
        signIn("192.168.1.102", "TI").andExpect(status().isOk());
        signIn("192.168.1.103", "TJ")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("set up for 2 counters")));

        logOut(leaving);

        signIn("192.168.1.103", "TJ").andExpect(status().isOk());
    }

    /**
     * Signing in once did not used to buy a counter a place for ever, but that is exactly what it
     * did: the cap was only checked when a machine had no row in the table yet, so a shop set up
     * for two counters could have three billing at once as long as each had signed in at some
     * point in the past. The number in pos.clients.max is a limit on machines billing, not on
     * machines the server has ever heard of.
     */
    @Test
    void aCounterThatHasBilledHereBeforeIsStillCountedAgainstTheLimit() throws Exception {
        String known = tokenOf(signIn("192.168.1.111", "TK").andExpect(status().isOk()));
        logOut(known);

        signIn("192.168.1.112", "TL").andExpect(status().isOk());
        signIn("192.168.1.113", "TM").andExpect(status().isOk());

        signIn("192.168.1.111", "TK")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("TL")))
                .andExpect(jsonPath("$.counterSetup").value(true));
    }

    /**
     * A counter's settings window has to know whether the shop has room for it before anybody has
     * signed in - which is the one moment there is no token to ask with. It gives away nothing the
     * server does not already broadcast to the whole shop network when a counter looks for it.
     */
    @Test
    void aCounterCanAskForRoomBeforeAnybodySignsIn() throws Exception {
        JsonNode room = places("TN");
        assertThat(room.get("canJoin").asBoolean()).isTrue();
        assertThat(room.get("places").asInt()).isEqualTo(2);
        assertThat(room.get("free").asInt()).isEqualTo(2);
        assertThat(room.get("holdsPlace").asBoolean()).isFalse();

        signIn("192.168.1.121", "TO").andExpect(status().isOk());
        String second = tokenOf(signIn("192.168.1.122", "TP").andExpect(status().isOk()));

        JsonNode full = places("TN");
        assertThat(full.get("free").asInt()).isZero();
        assertThat(full.get("canJoin").asBoolean()).isFalse();
        assertThat(full.get("refusal").asText()).contains("set up for 2 counters");

        // A counter already billing here is coming back to its own place, not asking for a new
        // one, so a full shop must still let it in - otherwise a reboot locks a till out.
        JsonNode itsOwn = places("TP");
        assertThat(itsOwn.get("holdsPlace").asBoolean()).isTrue();
        assertThat(itsOwn.get("canJoin").asBoolean()).isTrue();
        assertThat(second).isNotNull();
    }

    /**
     * The last place, wanted by two machines at once. Both are told there is room, both press Save
     * and start billing, and both reach the sign-in screen - so the question is settled where it
     * has to be, in the transaction that writes the sign-in. One bills; the other is told why in a
     * sentence, and marked as a machine problem so its own settings window can come back.
     */
    @Test
    void onlyOneOfTwoCountersGetsTheLastPlace() throws Exception {
        signIn("192.168.1.131", "TQ").andExpect(status().isOk());
        assertThat(places("TR").get("canJoin").asBoolean()).isTrue();
        assertThat(places("TS").get("canJoin").asBoolean()).isTrue();

        signIn("192.168.1.132", "TR").andExpect(status().isOk());

        signIn("192.168.1.133", "TS")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.counterSetup").value(true))
                .andExpect(jsonPath("$.detail").value(containsString("wait for one to sign out")));
    }

    private JsonNode places(String counter) throws Exception {
        String body = mvc.perform(get("/api/clients/places?counter=" + counter)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .header(HttpHeaders.HOST, "localhost"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private void logOut(String token) throws Exception {
        mvc.perform(post("/api/auth/logout")
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .header(HttpHeaders.HOST, "localhost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    /** A counter saying it is still there, the way every signed-in screen does on a timer. */
    private ResultActions stillHere(String token, String counter) throws Exception {
        return mvc.perform(post("/api/clients/here")
                .header(LocalApiGuard.CLIENT_HEADER, "test")
                .header(HttpHeaders.HOST, "localhost")
                .header(LocalApiGuard.COUNTER_HEADER, counter)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private JsonNode tillSeenBy(String token, String counter) throws Exception {
        return tillSeenBy(token, counter, counter);
    }

    /** One till's row out of the shop's list, as the settings screen draws it. */
    private JsonNode tillSeenBy(String token, String counter, String code) throws Exception {
        JsonNode body = json.readTree(settingsSeenBy(token, counter).andReturn().getResponse().getContentAsString());
        for (JsonNode till : body.get("terminals")) {
            if (till.get("code").asText().equals(code)) {
                return till;
            }
        }
        throw new AssertionError("No till " + code + " in the shop's list");
    }

    private ResultActions settingsSeenBy(String token, String counter) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/settings")
                .header(LocalApiGuard.CLIENT_HEADER, "test")
                .header(HttpHeaders.HOST, "localhost")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (counter != null) {
            request = request.header(LocalApiGuard.COUNTER_HEADER, counter);
        }
        return mvc.perform(request).andExpect(status().isOk());
    }

    private String counterOf(ResultActions signedIn) throws Exception {
        JsonNode body = json.readTree(signedIn.andReturn().getResponse().getContentAsString());
        return body.get("user").get("counter").asText();
    }

    private String tokenOf(ResultActions signedIn) throws Exception {
        return json.readTree(signedIn.andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions signIn(String fromAddress, String counter) throws Exception {
        return signIn(fromAddress, counter, null);
    }

    private ResultActions signIn(String fromAddress, String counter, String name) throws Exception {
        MockHttpServletRequestBuilder request = login(name).with(from(fromAddress));
        if (counter != null) {
            request = request.header(LocalApiGuard.COUNTER_HEADER, counter);
        }
        return mvc.perform(request);
    }

    private MockHttpServletRequestBuilder login() {
        return login(null);
    }

    private MockHttpServletRequestBuilder login(String counterName) {
        return post("/api/auth/login")
                .header(LocalApiGuard.CLIENT_HEADER, "test")
                .header(HttpHeaders.HOST, "localhost")
                .contentType(APPLICATION_JSON)
                .content(counterName == null
                        ? "{\"username\":\"asha\",\"pin\":\"%s\"}".formatted(TestAccounts.CHOSEN_PIN)
                        : "{\"username\":\"asha\",\"pin\":\"%s\",\"counterName\":\"%s\"}"
                                .formatted(TestAccounts.CHOSEN_PIN, counterName));
    }

    /** MockMvc speaks from loopback unless told otherwise, which is the server's own browser. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
