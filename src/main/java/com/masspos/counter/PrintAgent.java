package com.masspos.counter;

import com.masspos.hardware.EscPos;
import com.masspos.hardware.FilePrinterConnection;
import com.masspos.hardware.PrinterConnection;
import com.masspos.hardware.PrinterProperties;
import com.masspos.hardware.PrinterStatus;
import com.masspos.hardware.SerialPrinterConnection;
import com.masspos.hardware.TcpPrinterConnection;
import com.masspos.hardware.WindowsPrinterConnection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The reason a counter is more than a browser window.
 *
 * <p>A counter has no server, so the machine holding the database cannot reach the printer
 * standing on the counter. This is the piece that closes that gap: a listener on the counter's own
 * loopback that takes a bill the server has already laid out and writes it to the printer and the
 * cash drawer beside the person who rang it up.
 *
 * <p>Deliberately small. It does not know what a bill is, what it costs or who sold it - it is
 * handed bytes and a job either reaches the printer or it does not. Everything that decides what
 * those bytes say still happens on the server, once, in the code that has always laid bills out.
 *
 * <p>It does own one thing, because only this machine can: <em>which</em> printer that is. The
 * settings page on a counter reads and writes it here ({@code /settings}, {@code /status},
 * {@code /test-page}) rather than on the server, where it would have been setting the server's
 * printer and through it every other counter's.
 *
 * <h2>Why it is safe to open a port</h2>
 * <ul>
 *   <li>It binds loopback only, so nothing on the network can reach it - not even the server.</li>
 *   <li>Browsers may only call it from the shop server's own page: the agent answers the preflight
 *       for that one origin and refuses any other, and checks the Origin itself rather than
 *       trusting the browser to have done so.</li>
 *   <li>It writes to a printer. The worst a caller can do is waste paper, open a drawer that
 *       somebody is standing at - the same thing the till's own drawer key does - or point this
 *       one counter's bills at another printer installed on this same machine. All of it needs the
 *       machine itself, which is to say the printer and the drawer are already in reach.</li>
 * </ul>
 *
 * <p>Jobs go through one queue on one thread, for the same reason the server's printer does: two
 * bills interleaved on one port is a bill nobody can read.
 */
public final class PrintAgent implements AutoCloseable {

    private static final int BACKLOG = 4;
    /** A job is bytes; anything much larger than a long bill with a logo is not one. */
    private static final int MAX_JOB_BYTES = 2 * 1024 * 1024;
    /** Settings and a shop name are a few hundred bytes; anything else is not one of those. */
    private static final int MAX_SETTINGS_BYTES = 8 * 1024;
    private static final Duration STATUS_TIMEOUT = Duration.ofMillis(500);
    /** Room for a status round trip and a bill queued ahead of it, as the server allows. */
    private static final Duration AWAIT_MARGIN = Duration.ofSeconds(3);
    /** Asking a program on this same machine; anything slower than this is not it. */
    private static final Duration PROBE = Duration.ofMillis(800);
    /** Put on the settings queue by {@link #close()}, so the counter stops waiting for a person. */
    private static final String CLOSING = "\u0000closing";

    private final CounterConfig config;
    private final Path spoolFolder;
    private final BlockingQueue<Task> queue = new ArrayBlockingQueue<>(32);
    /** Held by anything that reads or writes {@link #config}; the printer thread never takes it. */
    private final Object settings = new Object();
    private volatile String allowedOrigin = "";
    private volatile String lastError = "";
    /**
     * The printer as one object, swapped whole when the settings screen changes it. The printer
     * thread reads this rather than the settings themselves: a half-applied change - a new type
     * with the old port still in it - would reach the paper.
     */
    private volatile PrinterProperties printer;
    private HttpServer http;
    private ExecutorService requests;
    private Thread worker;
    private volatile boolean running;
    /** Touched only by the printer thread, exactly as the server's connection is. */
    private PrinterConnection connection;
    private PrinterProperties openedFor;

    public PrintAgent(CounterConfig config, Path spoolFolder) {
        this.config = config;
        this.spoolFolder = spoolFolder;
        this.printer = config.printer();
    }

    /** A bill in both shapes: bytes for a thermal printer, lines for a driver one. */
    record Job(byte[] bytes, java.util.List<EscPos.Line> lines) {
    }

    /**
     * Work for the printer thread. Bills, a test page and a status query all queue here, for the
     * same reason the server funnels them through one thread: two of them on one port at once is a
     * bill nobody can read, and a settings change that closed a port mid-job would lose one.
     */
    private interface Task {
        void run(PrinterConnection connection);

        /** True when getting through means the printer is well again, so the last error can go. */
        default boolean clearsLastError() {
            return true;
        }
    }

    /** Starts listening. The port is the counter's own setting, so two tills can share a machine. */
    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.agentPort()), BACKLOG);
        http.createContext("/print", this::onPrint);
        http.createContext("/hello", this::onHello);
        http.createContext("/settings", this::onSettings);
        http.createContext("/setup", this::onSetup);
        http.createContext("/status", this::onStatus);
        http.createContext("/test-page", this::onTestPage);
        // A handful of threads, not the listener's own: asking the printer how it is waits for the
        // printer thread, and on the listener's thread that wait would hold up the next bill.
        requests = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "counter-agent-http");
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(requests);
        running = true;
        worker = new Thread(this::printLoop, "counter-print-agent");
        worker.setDaemon(true);
        worker.start();
        http.start();
    }

    /** The one page allowed to send this counter a bill: the shop server it is connected to. */
    public void serving(String serverUrl) {
        this.allowedOrigin = ServerFinder.normalise(serverUrl);
    }

    /**
     * Whether a counter is already running on this machine, and where it is billing.
     *
     * <p>Clicking the shortcut again must not leave a second copy of this program running in the
     * background: only one of them can hold the printer, but both stay alive, and a shop that
     * opens the till a few times a day ends the week with a row of them in Task Manager. The one
     * already running answers here, and the new process opens a window on the same server and
     * stops.
     *
     * @return the server the running counter is billing against, or empty when none is running
     */
    public static Optional<String> runningOn(int agentPort) {
        try {
            HttpURLConnection http = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:" + agentPort + "/hello").toURL().openConnection();
            http.setConnectTimeout((int) PROBE.toMillis());
            http.setReadTimeout((int) PROBE.toMillis());
            if (http.getResponseCode() != 200) {
                return Optional.empty();
            }
            try (java.io.InputStream answer = http.getInputStream()) {
                return Optional.of(PrintJobs.serverOf(answer.readAllBytes()));
            } finally {
                http.disconnect();
            }
        } catch (IOException | RuntimeException nothingThere) {
            // Nothing listening, or something that is not our agent: start normally and let the
            // bind fail loudly if that port really is somebody else's.
            return Optional.empty();
        }
    }

    public int port() {
        return config.agentPort();
    }

    /** The last job that failed, for the settings screen to show. Empty once one succeeds. */
    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        running = false;
        if (http != null) {
            http.stop(0);
        }
        if (requests != null) {
            requests.shutdownNow();
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** Lets the page check the agent is running before it tries to bill through it. */
    private void onHello(HttpExchange exchange) throws IOException {
        if (cors(exchange)) {
            return;
        }
        // The paper, so the server can lay a bill out for this counter's roll. Only the counter
        // knows how wide its own is, and the page has to pass it on when it asks for a bill.
        PrinterProperties printer = this.printer;
        respond(exchange, 200, "{"
                + "\"counter\":\"" + config.code() + "\","
                // Where this counter is billing, so a second click of the shortcut can open a
                // window on the same server instead of starting another copy of this program.
                + "\"server\":\"" + allowedOrigin.replace("\\", "\\\\").replace("\"", "\\\"") + "\","
                + "\"printer\":\"" + printer.type() + "\","
                + "\"columns\":" + printer.columns() + ","
                + "\"codePage\":\"" + printer.codePage() + "\","
                + "\"drawerPin\":\"" + printer.drawerPin() + "\","
                + "\"drawerPulseMillis\":" + printer.drawerPulse().toMillis() + ","
                + "\"lastError\":\"" + lastError.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + "}");
    }

    private void onPrint(HttpExchange exchange) throws IOException {
        if (cors(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"send the bill with POST\"}");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_JOB_BYTES);
        if (body.length == 0) {
            respond(exchange, 400, "{\"error\":\"nothing to print\"}");
            return;
        }
        Job job;
        try {
            job = PrintJobs.read(body);
        } catch (RuntimeException notAJob) {
            respond(exchange, 400, "{\"error\":\"that is not a bill this counter can print\"}");
            return;
        }
        if (!queue.offer(write(job))) {
            respond(exchange, 503, "{\"error\":\"the printer is behind; try again in a moment\"}");
            return;
        }
        // Answered as soon as the job is taken, not when the paper comes out: the screen must not
        // wait on a printer, and a failure is reported the same way the server's always has been.
        respond(exchange, 202, "{\"queued\":true}");
    }

    /**
     * This counter's own printer, read and set from the settings screen.
     *
     * <p>The page is the server's, but the printer it is choosing is the one standing here, so the
     * choice is saved here too - in this machine's {@code counter.properties}. Sending it to the
     * server would set the <em>server's</em> printer, which is how one counter used to take the
     * back office's bills with it.
     */
    private void onSettings(HttpExchange exchange) throws IOException {
        if (cors(exchange)) {
            return;
        }
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            synchronized (settings) {
                respond(exchange, 200, CounterPrinter.json(CounterPrinter.setupOf(config, spoolFolder)));
            }
            return;
        }
        if (!"PUT".equalsIgnoreCase(method)) {
            respond(exchange, 405, "{\"error\":\"read the printer with GET, change it with PUT\"}");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_SETTINGS_BYTES);
        try {
            CounterPrinter.Setup saved;
            synchronized (settings) {
                saved = CounterPrinter.save(config, spoolFolder, CounterPrinter.read(body));
                printer = config.printer();
            }
            // The next job finds that the printer is no longer the one its connection was built
            // from and opens the new one, on the printer thread, between two bills.
            respond(exchange, 200, CounterPrinter.json(saved));
        } catch (IllegalArgumentException refused) {
            respond(exchange, 400, error(refused));
        } catch (RuntimeException cannotSave) {
            respond(exchange, 500, error(cannotSave));
        }
    }

    /**
     * "This shop will not have me." The sign-in screen calls this when the server refuses the
     * machine rather than the person - no counter number, a number that is the server's own, or a
     * shop with every place taken, which is what the counter that loses a race for the last one
     * is told.
     *
     * <p>Nothing on the web page can reopen a window belonging to this program, and nothing should
     * leave somebody staring at a sign-in screen that will never let them in. The agent is the one
     * piece of the counter that both ends can reach, so the request comes through here and the
     * counter's own settings window opens with the server's sentence on it.
     */
    private void onSetup(HttpExchange exchange) throws IOException {
        if (notThisMachine(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/setup") || path.equals("/setup/")) {
                page(exchange);
                return;
            }
            if (path.equals("/setup/state")) {
                synchronized (settings) {
                    respond(exchange, 200, CounterSetup.state(config));
                }
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"ask with POST\"}");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_SETTINGS_BYTES);
            if (path.equals("/setup/look")) {
                // On a scratch copy: nothing the page is still deciding touches the settings this
                // counter is running on until Save.
                CounterConfig asking = new CounterConfig();
                com.fasterxml.jackson.databind.JsonNode said = CounterSetup.read(body);
                asking.find(CounterSetup.find(said.path("find").asText("")));
                asking.address(said.path("address").asText(""));
                asking.url(said.path("url").asText(""));
                synchronized (settings) {
                    asking.counter(config.counterId(), config.code(), config.name());
                }
                respond(exchange, 200, CounterSetup.look(asking));
                return;
            }
            if (path.equals("/setup/save")) {
                com.fasterxml.jackson.databind.node.ObjectNode answer = CounterSetup.object();
                Optional<String> server;
                synchronized (settings) {
                    server = CounterSetup.save(config, CounterSetup.read(body), answer);
                }
                if (server.isPresent()) {
                    serving(server.get());
                    synchronized (settings) {
                        answer.put("billing", CounterApp.billingScreen(config, server.get()));
                    }
                }
                respond(exchange, 200, CounterSetup.bytes(answer));
                return;
            }
            respond(exchange, 404, "{\"error\":\"no such setup page\"}");
        } catch (RuntimeException unexpected) {
            // Nothing here may take the agent down: this is the one screen a counter that cannot
            // reach its shop still has.
            respond(exchange, 500, "{\"error\":\"This counter's setup service hit a problem."
                    + " Close this window and start GO RockIt Counter again.\"}");
        }
    }

    private void page(HttpExchange exchange) throws IOException {
        byte[] html = CounterSetup.page();
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, html.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(html);
        }
    }

    /**
     * Setting this counter up is for the person at this machine, not for a page from anywhere
     * else: these are the only requests that change which shop the counter bills against. A
     * browser sends its own origin on a cross-site POST, so anything but this agent's own page is
     * refused. The port is loopback already, so nothing on the network reaches here at all.
     */
    private boolean notThisMachine(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank() || origin.equals(ownOrigin())) {
            return false;
        }
        respond(exchange, 403, "{\"error\":\"this page can only be opened on the counter itself\"}");
        return true;
    }

    private String ownOrigin() {
        return "http://127.0.0.1:" + config.agentPort();
    }

    /** Where to send somebody to set this counter up, or to put right what the shop refused. */
    public String setupUrl() {
        return ownOrigin() + "/setup";
    }

    /** Paper and cover on this counter's printer, for the lines under the settings page. */
    private void onStatus(HttpExchange exchange) throws IOException {
        if (cors(exchange)) {
            return;
        }
        CompletableFuture<PrinterStatus> answer = new CompletableFuture<>();
        Task ask = new Task() {
            @Override
            public void run(PrinterConnection connection) {
                answer.complete(PrinterStatus.query(connection, STATUS_TIMEOUT));
            }

            @Override
            public boolean clearsLastError() {
                // A printer that answers a status query has not printed the bill that failed.
                return false;
            }
        };
        if (!queue.offer(ask)) {
            respond(exchange, 503, "{\"error\":\"the printer is behind; try again in a moment\"}");
            return;
        }
        try {
            respond(exchange, 200, CounterPrinter.json(answer.get(waitLimit().toMillis(), TimeUnit.MILLISECONDS)));
        } catch (ExecutionException | TimeoutException silent) {
            // A port that cannot be opened at all is not a status, exactly as on the server.
            respond(exchange, 200, CounterPrinter.json(PrinterStatus.NOT_RESPONDING));
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, "{\"error\":\"the counter is shutting down\"}");
        }
    }

    /** A page out of this counter's own printer, which is the only proof the setting took. */
    private void onTestPage(HttpExchange exchange) throws IOException {
        if (cors(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"ask for a test page with POST\"}");
            return;
        }
        String shop = PrintJobs.shopName(exchange.getRequestBody().readNBytes(MAX_SETTINGS_BYTES));
        EscPos page;
        synchronized (settings) {
            page = CounterPrinter.testPage(config, spoolFolder, shop);
        }
        if (!queue.offer(write(page))) {
            respond(exchange, 503, "{\"error\":\"the printer is behind; try again in a moment\"}");
            return;
        }
        respond(exchange, 202, "{\"queued\":true}");
    }

    private Task write(Job job) {
        return connection -> connection.write(EscPos.rendered(job.bytes(), job.lines()));
    }

    private Task write(EscPos document) {
        return connection -> connection.write(document);
    }

    /** As long as this counter's printer is allowed for one job, plus room for one queued ahead. */
    private Duration waitLimit() {
        return printer.timeout().plus(AWAIT_MARGIN);
    }

    private static byte[] error(RuntimeException refused) {
        return CounterPrinter.json(Map.of("error", wordsFor(refused)));
    }

    /**
     * What the screen is told. Every refusal out here is one this counter worded itself, so a
     * message is shown as it is; an exception with none was not written for anybody, and its class
     * name would be the only thing on a printer settings screen a manager is looking at.
     */
    private static String wordsFor(RuntimeException failed) {
        String said = failed.getMessage();
        if (said == null || said.isBlank()) {
            CounterApp.note("The printer failed with nothing to say: " + failed);
            return "The printer on this counter did not answer. Try again, and if it keeps"
                    + " happening call service.";
        }
        return said;
    }

    /**
     * Returns true when the exchange is finished here: a preflight, or an origin that is not the
     * shop server this counter is connected to.
     */
    private boolean cors(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        boolean allowed = origin == null || origin.isBlank() || origin.equals(allowedOrigin);
        if (allowed && origin != null && !origin.isBlank()) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "content-type");
            // PUT is not one of the methods a browser will send cross-origin unless it is named
            // here, and saving the printer is a PUT: without this the settings page cannot save.
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Max-Age", "600");
        }
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(allowed ? 204 : 403, -1);
            exchange.close();
            return true;
        }
        if (!allowed) {
            respond(exchange, 403, "{\"error\":\"this counter only prints for its own shop server\"}");
            return true;
        }
        return false;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * One job at a time, on one thread, holding the connection open between bills the way the
     * server's printer does. A failure is remembered and the next job still tried: a jammed
     * printer should not need the counter restarted once the paper is back in.
     */
    private void printLoop() {
        while (running) {
            Task task;
            try {
                task = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                break;
            }
            if (task == null) {
                continue;
            }
            try {
                task.run(connectionFor(printer));
                if (task.clearsLastError()) {
                    lastError = "";
                }
            } catch (RuntimeException failed) {
                lastError = wordsFor(failed);
                closeOpen();
            }
        }
        closeOpen();
    }

    /**
     * The connection for the printer as it is set now, rebuilt when the settings screen has changed
     * it - the same bargain the server's {@code PrinterConnections} strikes, and for the same
     * reason: a printer is only ever swapped between two jobs, on the thread that writes them.
     */
    private PrinterConnection connectionFor(PrinterProperties now) {
        if (connection != null && now.equals(openedFor)) {
            return connection;
        }
        closeOpen();
        connection = connect(now, spoolFolder);
        openedFor = now;
        return connection;
    }

    private void closeOpen() {
        connection = closeQuietly(connection);
        openedFor = null;
    }

    private static PrinterConnection closeQuietly(PrinterConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException alreadyGone) {
                // Nothing useful to do; the next job opens a fresh one.
            }
        }
        return null;
    }

    /**
     * The same four transports the server has always had, built here instead. Opening touches no
     * hardware until a job is written, so a printer that is off cannot stop the counter starting.
     */
    static PrinterConnection connect(PrinterProperties printer, Path spoolFolder) {
        return switch (printer.type()) {
            case SERIAL -> new SerialPrinterConnection(printer.serial(), printer.timeout());
            case TCP -> new TcpPrinterConnection(printer.tcp(), printer.timeout());
            case WINDOWS -> new WindowsPrinterConnection(printer.windows());
            case FILE -> new FilePrinterConnection(spoolFolder);
        };
    }
}
