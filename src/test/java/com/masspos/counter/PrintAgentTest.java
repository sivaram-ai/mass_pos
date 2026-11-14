package com.masspos.counter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The printer standing on a counter, read and set from the settings screen.
 *
 * <p>A counter's printer is its own. Before this, the printer page on a counter was asking the
 * shop's server about the server's printer: a manager at Counter 2 who chose a printer there set
 * the back office's, and with it every other counter's. The page asks this agent instead, so what
 * is checked here is that the answer describes <em>this</em> machine and that saving touches
 * nothing but this machine's own settings file.
 */
class PrintAgentTest {

    /** A bill as the server sends one: bytes for a thermal printer, a line for a driver one. */
    private static final String BILL = """
            {"escpos":"G0BoaQ==","lines":[{"text":"MASS MART","align":"CENTER"}]}""";

    @TempDir
    Path folder;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private PrintAgent agent;
    private String base;
    private Path spool;
    private int port;

    @BeforeEach
    void aCounterWhosePrinterWritesToAFolder() throws IOException {
        port = freePort();
        Files.writeString(folder.resolve("counter.properties"), """
                counter.code=T2
                counter.name=Counter 2
                printer.type=FILE
                printer.columns=48
                print-agent.port=%d
                """.formatted(port));
        spool = folder.resolve("printer-out");
        agent = new PrintAgent(CounterConfig.loadFrom(folder), spool);
        agent.start();
        base = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void closed() {
        agent.close();
    }

    /** The printer named is the one on this machine, with this machine's ports to choose from. */
    @Test
    void answersWithThePrinterStandingOnThisCounter() throws Exception {
        JsonNode setup = json.readTree(get("/settings").body());

        assertThat(setup.at("/printer/type").asText()).isEqualTo("FILE");
        assertThat(setup.at("/printer/columns").asInt()).isEqualTo(48);
        assertThat(setup.get("target").asText()).contains("FILE", spool.toAbsolutePath().toString());
        assertThat(setup.get("serialPorts").isArray()).isTrue();
        assertThat(setup.get("installedPrinters").isArray()).isTrue();
    }

    /**
     * Saving writes this counter's own file and nothing else. The columns matter beyond the form:
     * the server lays each bill out for the roll the counter says it has, so a 58 mm roll chosen
     * here has to be what {@code /hello} reports from then on.
     */
    @Test
    void savesThePrinterIntoThisCountersOwnSettingsFile() throws Exception {
        HttpResponse<String> saved = send("PUT", "/settings", form(32, 20));

        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).at("/printer/columns").asInt()).isEqualTo(32);
        assertThat(Files.readString(folder.resolve("counter.properties")))
                .contains("printer.columns=32", "printer.timeout-seconds=20");
        assertThat(CounterConfig.loadFrom(folder).printer().columns()).isEqualTo(32);
        assertThat(json.readTree(get("/hello").body()).get("columns").asInt()).isEqualTo(32);
    }

    /**
     * Setting a counter up is a page this machine serves itself. It has to be here before the
     * shop's server has been found - that is the whole reason it is here rather than on the
     * server - so it is served whether or not this counter has ever reached one.
     */
    @Test
    void servesTheSetupPageWithoutAShopServerAnywhere() throws Exception {
        HttpResponse<String> page = get("/setup");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(header(page, "Content-Type")).contains("text/html");
        assertThat(page.body()).contains("Set this counter up", "Which counter is this machine?");
    }

    /** The page opens on the settings this machine already has, rather than empty. */
    @Test
    void tellsTheSetupPageWhatThisCounterAlreadyIs() throws Exception {
        JsonNode state = json.readTree(get("/setup/state").body());

        assertThat(state.get("find").asText()).isEqualTo("AUTOMATIC");
        assertThat(state.get("counterCode").asText()).isEqualTo("T2");
    }

    /** No shop server out there: the page is told so, and what to go and check. */
    @Test
    void saysWhatToCheckWhenNoShopServerAnswers() throws Exception {
        HttpResponse<String> looked = send("POST", "/setup/look",
                "{\"find\":\"ADDRESS\",\"address\":\"127.0.0.1:1\"}");

        JsonNode answer = json.readTree(looked.body());
        assertThat(answer.get("found").asBoolean()).isFalse();
        assertThat(answer.get("trouble").asText()).contains("Nothing answered at that address");
        assertThat(answer.get("steps")).isNotEmpty();
    }

    /** And saving is refused, in words, rather than writing settings that cannot work. */
    @Test
    void refusesToSaveACounterItCannotReach() throws Exception {
        HttpResponse<String> saved = send("POST", "/setup/save",
                "{\"find\":\"ADDRESS\",\"address\":\"127.0.0.1:1\",\"counterId\":\"01a1-not-real\"}");

        JsonNode answer = json.readTree(saved.body());
        assertThat(answer.has("billing")).isFalse();
        assertThat(answer.get("error").asText()).contains("stopped answering");
    }

    /**
     * These are the only requests that change which shop a counter bills against, so they are for
     * the person at this machine alone. A browser sends its own origin on a cross-site POST.
     */
    @Test
    void refusesSetupFromAnyPageButItsOwn() throws Exception {
        HttpResponse<String> elsewhere = http.send(HttpRequest.newBuilder(URI.create(base + "/setup/save"))
                .header("Origin", "http://evil.example")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(elsewhere.statusCode()).isEqualTo(403);
    }

    /**
     * A second click of the shortcut has to find the copy already running rather than start
     * another one, and it finds it here - along with where that copy is billing, so the window it
     * opens points at the same server.
     */
    @Test
    void saysWhereACounterAlreadyRunningOnThisMachineIsBilling() throws Exception {
        agent.serving("http://192.168.1.10:8765/");

        assertThat(json.readTree(get("/hello").body()).get("server").asText())
                .isEqualTo("http://192.168.1.10:8765");
        assertThat(PrintAgent.runningOn(port)).contains("http://192.168.1.10:8765");
    }

    /** Nothing listening is the ordinary case: this machine has no counter running on it. */
    @Test
    void saysNoCounterIsRunningWhenNothingAnswers() throws Exception {
        assertThat(PrintAgent.runningOn(freePort())).isEmpty();
    }

    /** Which counter this is and where its server lives are not the browser's to rewrite. */
    @Test
    void leavesTheCounterNumberAndItsServerAlone() throws Exception {
        send("PUT", "/settings", form(32, 20));

        CounterConfig after = CounterConfig.loadFrom(folder);
        assertThat(after.code()).isEqualTo("T2");
        assertThat(after.name()).isEqualTo("Counter 2");
    }

    /** A printer the counter could not print with is refused with the sentence shown on screen. */
    @Test
    void refusesAPrinterItCouldNotPrintWith() throws Exception {
        HttpResponse<String> refused = send("PUT", "/settings", form(4, 20));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json.readTree(refused.body()).get("error").asText())
                .contains("characters per line", "between 24 and 64");
        assertThat(CounterConfig.loadFrom(folder).printer().columns())
                .as("nothing is saved when anything is refused").isEqualTo(48);
    }

    /**
     * A test page out of this counter's own printer, naming the machine: somebody holding two test
     * pages has to be able to tell which printer each came from.
     */
    @Test
    void printsATestPageOnThisCountersOwnPrinter() throws Exception {
        assertThat(send("POST", "/test-page", "{\"shop\":\"MASS MART\"}").statusCode()).isEqualTo(202);

        assertThat(Files.readString(printedWithin(Duration.ofSeconds(5))))
                .contains("TEST PAGE", "MASS MART", "Counter 2 (T2)");
    }

    /**
     * The printer just chosen is the one the next bill goes to. The connection is held open between
     * bills, so without noticing that the settings have changed a counter would keep printing on
     * the printer it started with and the save would look as though it had not taken.
     *
     * <p>Shown by choosing a printer that cannot work: the bill before the change reaches the
     * folder, the one after it reports a printer that will not open.
     */
    @Test
    void sendsTheNextBillToThePrinterJustChosen() throws Exception {
        assertThat(send("POST", "/print", BILL).statusCode()).isEqualTo(202);
        printedWithin(Duration.ofSeconds(5));
        assertThat(json.readTree(get("/hello").body()).get("lastError").asText()).isEmpty();

        assertThat(send("PUT", "/settings", tcpForm(freePort())).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/print", BILL).statusCode()).isEqualTo(202);

        assertThat(failureWithin(Duration.ofSeconds(10))).contains("127.0.0.1");
    }

    /** A printer that cannot answer a status query is not an error: it is a FILE or a dumb one. */
    @Test
    void saysThePrinterHereIsNotAnsweringRatherThanFailing() throws Exception {
        HttpResponse<String> status = get("/status");

        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(json.readTree(status.body()).get("responding").asBoolean()).isFalse();
    }

    /** Only the shop server's page may reach this agent, whatever it is being asked for. */
    @Test
    void refusesAPageFromAnywhereButItsOwnShopServer() throws Exception {
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(URI.create(base + "/settings"))
                .header("Origin", "http://evil.example").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(403);
    }

    /**
     * The shop server's own page has to be able to save through this, which takes more than being
     * allowed: a browser will not send a PUT across origins unless the preflight names the method.
     * Without that line the settings screen on a counter cannot save and says nothing useful about
     * why, so the preflight is checked rather than assumed.
     */
    @Test
    void letsTheShopServersOwnPageSaveThePrinterHere() throws Exception {
        String origin = "http://192.168.1.10:8765";
        agent.serving(origin);

        HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(URI.create(base + "/settings"))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(preflight.statusCode()).isEqualTo(204);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
        assertThat(header(preflight, "Access-Control-Allow-Methods")).contains("PUT");
        assertThat(header(preflight, "Access-Control-Allow-Headers")).contains("content-type");

        HttpResponse<String> saved = http.send(HttpRequest.newBuilder(URI.create(base + "/settings"))
                .header("Origin", origin).header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(form(32, 5))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(saved.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
    }

    /**
     * The agent answers as soon as it has taken the job, not when the paper is out - the same
     * bargain the server's printer makes - so the preview is waited for rather than expected.
     */
    /**
     * Waits for a printed page to be <em>finished</em>, not merely started. The agent answers as
     * soon as it has taken the job and the printer thread writes it afterwards, so a file that
     * exists may still be empty for a moment - which read a blank page about one run in twenty.
     */
    private Path printedWithin(Duration limit) throws Exception {
        Instant giveUp = Instant.now().plus(limit);
        while (Instant.now().isBefore(giveUp)) {
            if (Files.isDirectory(spool)) {
                try (Stream<Path> files = Files.list(spool)) {
                    Optional<Path> preview = files
                            .filter(file -> file.getFileName().toString().endsWith(".txt"))
                            .filter(file -> sizeOf(file) > 0)
                            .findFirst();
                    if (preview.isPresent()) {
                        return preview.get();
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Nothing was printed on this counter within " + limit);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (java.io.IOException beingWritten) {
            return 0;
        }
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    /** The last job's failure, as the settings screen reads it off {@code /hello}. */
    private String failureWithin(Duration limit) throws Exception {
        Instant giveUp = Instant.now().plus(limit);
        String said = "";
        while (Instant.now().isBefore(giveUp)) {
            said = json.readTree(get("/hello").body()).get("lastError").asText();
            if (!said.isEmpty()) {
                return said;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The bill went somewhere without complaining: lastError is still empty");
    }

    /** A printer on a port nothing is listening on, so opening it is bound to fail. */
    private static String tcpForm(int closedPort) {
        return """
                {"type":"TCP","serialPort":"","serialBaudRate":9600,"serialFlowControl":"NONE",
                 "tcpHost":"127.0.0.1","tcpPort":%d,"windowsPrinter":"","windowsWidthMm":80,
                 "columns":48,"codePage":"PC437","drawerPin":"PIN_2","drawerPulseMillis":100,
                 "timeoutSeconds":1}""".formatted(closedPort);
    }

    private static String form(int columns, int timeoutSeconds) {
        return """
                {"type":"FILE","serialPort":"","serialBaudRate":9600,"serialFlowControl":"NONE",
                 "tcpHost":"","tcpPort":9100,"windowsPrinter":"","windowsWidthMm":80,
                 "columns":%d,"codePage":"PC437","drawerPin":"PIN_2","drawerPulseMillis":100,
                 "timeoutSeconds":%d}""".formatted(columns, timeoutSeconds);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The counter's own port may be taken by a counter app running on this machine. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
