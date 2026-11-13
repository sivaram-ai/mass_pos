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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    /** Only an admin may sign another till out, so the shop has one. */
    private static String adminToken;

    /**
     * An empty shop before each test. The places are shop-wide and a counter holds its one for
     * hours, which is the behaviour being tested - so tests that each need a free place have to
     * stop inheriting the counters the last one left behind.
     */
    @BeforeEach
    void anEmptyShopWithOneCashier() {
        if (!staffReady) {
            TestAccounts.tokenFor(auth, "asha", UserRole.CASHIER);
            adminToken = TestAccounts.tokenFor(auth, "owner", UserRole.ADMIN);
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

        // A third counter cannot be created at all now - the licence covers two - so the machine
        // after the last one is turned away where it asks to exist, not later at the till.
        mvc.perform(asAdmin(post("/api/terminals")).content(newCounter("TC", "Counter C")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").value(containsString("Maximum counter limit reached")));
        // The two the shop does have are both billing, so every place is taken.
        assertThat(places("TA").get("free").asInt()).isZero();
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
    void aMachineWithNoCounterIsToldToChooseOne() throws Exception {
        signIn("192.168.1.81", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("has not been given a counter yet")));
    }

    /** A machine claiming a till this shop does not have gets the same answer, not a stack trace. */
    @Test
    void aMachineClaimingATillThisShopDoesNotHaveIsRefused() throws Exception {
        mvc.perform(login("01a1cb62-dead-75cd-be17-fa6db596caa3").with(from("192.168.1.82")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.counterSetup").value(true));
        mvc.perform(login("not-an-id").with(from("192.168.1.83")))
                .andExpect(status().isForbidden());
    }

    /**
     * `pos.clients.max` is how many counters the shop may have, not only how many may bill at once.
     * It used to cap the sign-ins alone, so a shop licensed for two could create ten counters and
     * only find out at the till - with no error anywhere in between.
     */
    @Test
    void willNotCreateMoreCountersThanTheLicenceCovers() throws Exception {
        counterId("TM", "Counter M");
        counterId("TN", "Counter N");

        mvc.perform(asAdmin(post("/api/terminals")).content(newCounter("TO", "One too many")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code")
                        .value(containsString("Maximum counter limit reached")))
                .andExpect(jsonPath("$.errors.code")
                        .value(containsString("only 2 additional counters")));

        // And nothing was created: the shop still has exactly the two it is licensed for.
        assertThat(terminals.findByCode("TO")).isEmpty();
        JsonNode tills = json.readTree(settingsSeenBy(adminToken, null).andReturn()
                .getResponse().getContentAsString());
        assertThat(tills.get("terminals").findValuesAsText("code")).doesNotContain("TO");
    }

    /**
     * A shop can end up over its limit without doing anything wrong - a licence cut from three
     * counters to one leaves the other two rows, and their bills. They are kept and marked rather
     * than deleted, and nothing can bill on them: the oldest counters are the ones the licence
     * covers, so which of them works does not move about as they are renamed.
     */
    @Test
    void marksAndRefusesTheCountersBeyondTheLicence() throws Exception {
        counterId("TP", "Counter P");
        counterId("TQ", "Counter Q");
        // A third, as a shop whose licence has been cut would already have. Created directly,
        // because the endpoint now refuses to make one.
        terminals.save(new com.masspos.terminal.Terminal("TR", "Counter R", "",
                com.masspos.terminal.TerminalType.COUNTER));

        JsonNode room = places("TR");
        assertThat(room.get("canJoin").asBoolean()).isFalse();
        assertThat(room.get("refusal").asText())
                .contains("beyond what this shop's licence covers", "Maximum counter limit reached");

        signIn("192.168.1.161", "TR")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.counterSetup").value(true));

        // The two it is licensed for still work, and the screen can tell the extras apart.
        signIn("192.168.1.162", "TP").andExpect(status().isOk());
        JsonNode till = tillSeenBy(adminToken, null, "TR");
        assertThat(till.get("overLimit").asBoolean()).isTrue();
        assertThat(tillSeenBy(adminToken, null, "TP").get("overLimit").asBoolean()).isFalse();
    }

    /**
     * The counter's number is settled when it is created and never again: it is stamped into every
     * bill that till has issued, and those bills are what the shop files.
     */
    @Test
    void refusesACounterNumberThatCouldNotGoOnABill() throws Exception {
        for (String bad : List.of("COUNTER-TWO", "2T", "")) {
            mvc.perform(asAdmin(post("/api/terminals")).content(newCounter(bad, "Counter")))
                    .andExpect(status().isBadRequest());
        }
        // And no two tills may share one: both would number their bills the same.
        counterId("TZ", "Counter Z");
        mvc.perform(asAdmin(post("/api/terminals")).content(newCounter("TZ", "Another")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").value(containsString("already")));
    }

    /**
     * What a machine choosing which counter it is gets to choose from. Blocked counters are listed
     * too, and marked: a machine that simply could not find itself on the list would have no way
     * of telling whether it had been blocked or had never been created.
     */
    @Test
    void offersTheShopsCountersToAMachineChoosingWhichItIs() throws Exception {
        String blocked = counterId("TH", "Counter H");
        counterId("TI", "Counter I");
        mvc.perform(asAdmin(put("/api/terminals/" + blocked + "/blocked")).content("{\"blocked\":true}"))
                .andExpect(status().isNoContent());

        JsonNode list = json.readTree(mvc.perform(get("/api/clients/counters")
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .header(HttpHeaders.HOST, "localhost"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // The server's own till is not on it: no counter may be the server.
        assertThat(list.get("counters").findValuesAsText("code")).contains("TH", "TI").doesNotContain("T1");
        for (JsonNode counter : list.get("counters")) {
            if (counter.get("code").asText().equals("TH")) {
                assertThat(counter.get("blocked").asBoolean()).isTrue();
            }
        }
        signIn("192.168.1.58", "TH")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("blocked")));
        signIn("192.168.1.59", "TI").andExpect(status().isOk());
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
     * A counter is renamed on the server, and the new name follows it onto every screen. The
     * machine standing at that counter has no say in it: a name typed on one machine was a name
     * only that machine could correct, and the shop had no list of its own tills worth the name.
     */
    @Test
    void takesTheNameAnAdminGaveTheCounterOnTheServer() throws Exception {
        String counter = counterId("TF", "Coutner 2");
        String token = tokenOf(signIn("192.168.1.56", "TF").andExpect(status().isOk()));
        settingsSeenBy(token, "TF").andExpect(jsonPath("$.thisTerminal.name").value("Coutner 2"));

        mvc.perform(asAdmin(put("/api/terminals/" + counter))
                        .content("{\"name\":\"Counter 2\",\"section\":\"1st floor\"}"))
                .andExpect(status().isNoContent());

        settingsSeenBy(token, "TF")
                .andExpect(jsonPath("$.thisTerminal.name").value("Counter 2"))
                .andExpect(jsonPath("$.thisTerminal.section").value("1st floor"));
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
        assertThat(places("TH").get("free").asInt()).isZero();

        logOut(leaving);

        assertThat(places("TH").get("free").asInt()).isEqualTo(1);
        JsonNode till = tillSeenBy(adminToken, null, "TH");
        assertThat(till.get("signedIn").asBoolean()).isFalse();
        assertThat(till.get("staff").isNull()).isTrue();
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

        // A counter the shop has had for a while is one of its two, not a free extra on top: the
        // cap used to be checked only the first time a machine ever signed in, so a shop licensed
        // for two could end up with three billing as long as each had signed in at some point.
        mvc.perform(asAdmin(post("/api/terminals")).content(newCounter("TM", "Counter M")))
                .andExpect(status().isBadRequest());

        // And it can still come back to its own place afterwards.
        signIn("192.168.1.111", "TK").andExpect(status().isOk());
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

        String second = tokenOf(signIn("192.168.1.122", "TP").andExpect(status().isOk()));

        // A counter already billing here is coming back to its own place, not asking for a new
        // one, so it is never counted against itself - otherwise a reboot locks a till out.
        JsonNode itsOwn = places("TP");
        assertThat(itsOwn.get("holdsPlace").asBoolean()).isTrue();
        assertThat(itsOwn.get("canJoin").asBoolean()).isTrue();
        assertThat(itsOwn.get("free").asInt()).isEqualTo(1);
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
        signIn("192.168.1.132", "TR").andExpect(status().isOk());

        /*
         * With the licence capping how many counters the shop may have, every counter that exists
         * has a place, so two machines can no longer race for one. The refusal is kept as a
         * backstop for a shop whose licence has been cut below the counters it already has: that
         * third counter exists, and nothing may bill on it.
         */
        terminals.save(new com.masspos.terminal.Terminal("TS", "Counter S", "",
                com.masspos.terminal.TerminalType.COUNTER));

        signIn("192.168.1.133", "TS")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.counterSetup").value(true))
                .andExpect(jsonPath("$.detail").value(containsString("licence")));
    }

    /**
     * Pressing Log out on a counter has to free its place there and then. It appeared not to: the
     * counter app opens a window, and a window closed without logging out left its session open
     * behind the next one, so the till went on holding the shop's place and showing the cashier
     * who had left. Signing in on a counter now ends whatever was open on it.
     */
    @Test
    void loggingOutOfACounterLeavesNothingOpenBehindIt() throws Exception {
        // Three sign-ins on one counter, as a machine closed and reopened twice would make.
        signIn("192.168.1.141", "TT").andExpect(status().isOk());
        signIn("192.168.1.141", "TT").andExpect(status().isOk());
        String last = tokenOf(signIn("192.168.1.141", "TT").andExpect(status().isOk()));

        logOut(last);

        JsonNode after = places("TU");
        assertThat(after.get("free").asInt()).isEqualTo(2);
        JsonNode till = tillSeenBy(tokenOf(mvc.perform(login()).andExpect(status().isOk())), null, "TT");
        assertThat(till.get("signedIn").asBoolean()).isFalse();
        assertThat(till.get("inUse").asBoolean()).isFalse();
        assertThat(till.get("staff").isNull()).isTrue();
    }

    /** The sessions it ends are that counter's own; the back office is nobody's to sign out. */
    @Test
    void aCounterSigningInLeavesEveryOtherTillAlone() throws Exception {
        String office = tokenOf(mvc.perform(login()).andExpect(status().isOk()));
        signIn("192.168.1.142", "TV").andExpect(status().isOk());

        mvc.perform(get("/api/auth/me")
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .header(HttpHeaders.HOST, "localhost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + office))
                .andExpect(status().isOk());
    }

    /**
     * The machine that was switched off without logging out. Nothing it does can give the place
     * back, so an admin does it from here - and on a shop set up for one counter that is the
     * difference between the next machine billing now and not billing until this evening.
     */
    @Test
    void anAdminCanSignOutATillThatWasCarriedAway() throws Exception {
        signIn("192.168.1.151", "TW").andExpect(status().isOk());
        signIn("192.168.1.152", "TX").andExpect(status().isOk());
        assertThat(places("TW").get("free").asInt()).isZero();

        mvc.perform(post("/api/clients/TW/release")
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .header(HttpHeaders.HOST, "localhost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(places("TW").get("free").asInt()).isEqualTo(1);
        assertThat(tillSeenBy(adminToken, null, "TW").get("signedIn").asBoolean()).isFalse();
        // And it can come straight back: signing it out frees the place, it does not bar the till.
        signIn("192.168.1.151", "TW").andExpect(status().isOk());
    }

    private JsonNode places(String code) throws Exception {
        String counter = counterId(code, "Counter " + code);
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

    /**
     * A machine signing in as one of the shop's counters. The counter is created on the server
     * first, the way an admin creates one, because a machine can no longer make a counter up: it
     * is told which of the shop's tills it is, and sends that till's id.
     */
    private ResultActions signIn(String fromAddress, String code, String name) throws Exception {
        String id = code == null ? "" : counterId(code, name == null ? "Counter " + code : name);
        return mvc.perform(login(id).with(from(fromAddress)));
    }

    /** That counter's id, created through the admin's own endpoint if the shop has not got it. */
    private String counterId(String code, String name) throws Exception {
        java.util.Optional<String> known = terminals.findByCode(code)
                .map(terminal -> terminal.getId().toString());
        if (known.isPresent()) {
            return known.get();
        }
        String body = mvc.perform(asAdmin(post("/api/terminals")).content(newCounter(code, name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private MockHttpServletRequestBuilder login() {
        return login("");
    }

    private MockHttpServletRequestBuilder login(String counterId) {
        return post("/api/auth/login")
                .header(LocalApiGuard.CLIENT_HEADER, "test")
                .header(HttpHeaders.HOST, "localhost")
                .contentType(APPLICATION_JSON)
                .content(counterId == null || counterId.isEmpty()
                        ? "{\"username\":\"asha\",\"pin\":\"%s\"}".formatted(TestAccounts.CHOSEN_PIN)
                        : "{\"username\":\"asha\",\"pin\":\"%s\",\"counterId\":\"%s\"}"
                                .formatted(TestAccounts.CHOSEN_PIN, counterId));
    }

    private static String newCounter(String code, String name) {
        return "{\"code\":\"%s\",\"name\":\"%s\",\"section\":\"\"}".formatted(code, name);
    }

    /** The admin's own request, with the headers every call through the guard needs. */
    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(LocalApiGuard.CLIENT_HEADER, "test")
                .header(HttpHeaders.HOST, "localhost")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(APPLICATION_JSON);
    }

    /** MockMvc speaks from loopback unless told otherwise, which is the server's own browser. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
