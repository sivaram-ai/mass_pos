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

    private String counterOf(ResultActions signedIn) throws Exception {
        JsonNode body = json.readTree(signedIn.andReturn().getResponse().getContentAsString());
        return body.get("user").get("counter").asText();
    }

    private ResultActions signIn(String fromAddress, String counter) throws Exception {
        MockHttpServletRequestBuilder request = login().with(from(fromAddress));
        if (counter != null) {
            request = request.header(LocalApiGuard.COUNTER_HEADER, counter);
        }
        return mvc.perform(request);
    }

    private MockHttpServletRequestBuilder login() {
        return post("/api/auth/login")
                .header(LocalApiGuard.CLIENT_HEADER, "test")
                .header(HttpHeaders.HOST, "localhost")
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"asha\",\"pin\":\"%s\"}".formatted(TestAccounts.CHOSEN_PIN));
    }

    /** MockMvc speaks from loopback unless told otherwise, which is the server's own browser. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
