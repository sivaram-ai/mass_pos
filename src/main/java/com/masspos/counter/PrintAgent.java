package com.masspos.counter;

import com.masspos.hardware.EscPos;
import com.masspos.hardware.FilePrinterConnection;
import com.masspos.hardware.PrinterConnection;
import com.masspos.hardware.PrinterProperties;
import com.masspos.hardware.SerialPrinterConnection;
import com.masspos.hardware.TcpPrinterConnection;
import com.masspos.hardware.WindowsPrinterConnection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

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
 * <h2>Why it is safe to open a port</h2>
 * <ul>
 *   <li>It binds loopback only, so nothing on the network can reach it - not even the server.</li>
 *   <li>Browsers may only call it from the shop server's own page: the agent answers the preflight
 *       for that one origin and refuses any other, and checks the Origin itself rather than
 *       trusting the browser to have done so.</li>
 *   <li>It writes to a printer. The worst a caller can do is waste paper or open a drawer that
 *       somebody is standing at, which is the same thing the till's own drawer key does.</li>
 * </ul>
 *
 * <p>Jobs go through one queue on one thread, for the same reason the server's printer does: two
 * bills interleaved on one port is a bill nobody can read.
 */
public final class PrintAgent implements AutoCloseable {

    private static final int BACKLOG = 4;
    /** A job is bytes; anything much larger than a long bill with a logo is not one. */
    private static final int MAX_JOB_BYTES = 2 * 1024 * 1024;

    private final CounterConfig config;
    private final Path spoolFolder;
    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(32);
    private volatile String allowedOrigin = "";
    private volatile String lastError = "";
    private HttpServer http;
    private Thread worker;
    private volatile boolean running;

    public PrintAgent(CounterConfig config, Path spoolFolder) {
        this.config = config;
        this.spoolFolder = spoolFolder;
    }

    /** A bill in both shapes: bytes for a thermal printer, lines for a driver one. */
    record Job(byte[] bytes, java.util.List<EscPos.Line> lines) {
    }

    /** Starts listening. The port is the counter's own setting, so two tills can share a machine. */
    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.agentPort()), BACKLOG);
        http.createContext("/print", this::onPrint);
        http.createContext("/hello", this::onHello);
        http.setExecutor(null);
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
        PrinterProperties printer = config.printer();
        respond(exchange, 200, "{"
                + "\"counter\":\"" + config.code() + "\","
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
        if (!queue.offer(job)) {
            respond(exchange, 503, "{\"error\":\"the printer is behind; try again in a moment\"}");
            return;
        }
        // Answered as soon as the job is taken, not when the paper comes out: the screen must not
        // wait on a printer, and a failure is reported the same way the server's always has been.
        respond(exchange, 202, "{\"queued\":true}");
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
        PrinterConnection connection = null;
        while (running) {
            Job job;
            try {
                job = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                break;
            }
            if (job == null) {
                continue;
            }
            try {
                if (connection == null) {
                    connection = connect(config.printer(), spoolFolder);
                }
                connection.write(EscPos.rendered(job.bytes(), job.lines()));
                lastError = "";
            } catch (RuntimeException failed) {
                lastError = failed.getMessage() == null ? failed.toString() : failed.getMessage();
                connection = closeQuietly(connection);
            }
        }
        closeQuietly(connection);
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
