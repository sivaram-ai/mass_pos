package com.masspos.counter;

import com.masspos.desktop.Browser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * What the shortcut on a counter runs.
 *
 * <p>A counter is not a till with the database taken out - it is a different, much smaller
 * program. It holds no shop data, needs no licence and keeps nothing a shop would miss: its whole
 * state is one properties file. What it does have is the two things a browser window cannot do by
 * itself - find the shop's server, and drive the printer and cash drawer standing on this counter.
 *
 * <p>It shows no windows of its own. Setting a counter up used to mean a desktop dialog, for one
 * reason: everything it decides has to be settled <em>before</em> there is a shop server to serve
 * a page. The print agent is a web server on this machine that starts before anything has been
 * found, so the setup page comes from there instead and the whole product is one kind of thing -
 * a browser window - whether or not the shop can be reached.
 *
 * <p>In order: read the settings, start the agent, and open either the billing screen or, when
 * this machine has not been set up or its server cannot be found, the agent's own setup page.
 */
public final class CounterApp {

    /** Where FILE-type jobs land, which is what a counter prints to until a printer is chosen. */
    private static final String SPOOL = "printer-out";
    /** If the agent's own port is taken, the few after it, before giving up on printing. */
    private static final int PORTS_TO_TRY = 10;

    private CounterApp() {
    }

    public static void main(String[] args) {
        CounterConfig config = CounterConfig.load();
        try {
            if (openOnTheCounterAlreadyRunning(config)) {
                return;
            }
            PrintAgent agent = listening(config);
            if (agent == null) {
                // No port to serve the setup page from, so there is nothing to show and nowhere to
                // show it. The log is the only honest place left.
                note("Could not start this counter's print service on port "
                        + config.agentPort() + " or the " + PORTS_TO_TRY + " after it."
                        + " Close whatever is using those ports and start GO RockIt Counter again.");
                return;
            }
            Optional<String> server = config.readyToConnect() ? connect(config) : Optional.empty();
            if (server.isPresent()) {
                remember(config, server.get());
                agent.serving(server.get());
                Browser.openApp(billingScreen(config, server.get()));
            } else {
                // Not set up, or set up and the shop cannot be found. Both are the same screen.
                Browser.openApp(agent.setupUrl());
            }
            // The agent is the reason this process stays alive: the browser window is the shop's,
            // but the printer belongs to this program, and closing it would take the printer away.
            keepRunning();
        } catch (Exception unexpected) {
            note("This counter could not start: " + unexpected);
        }
    }

    /**
     * One counter process per machine.
     *
     * <p>Clicking the shortcut again used to start a whole second copy of this program. Only one
     * of them could hold the printer - the loser said so and then carried on running anyway - so a
     * shop that opens the till two or three times a day finished the week with a row of them in
     * Task Manager, each with its own JVM. Now the new process asks the one already running where
     * it is billing, opens a window there, and stops.
     *
     * @return true when a counter was already running and a window has been opened on it
     */
    private static boolean openOnTheCounterAlreadyRunning(CounterConfig config) throws IOException {
        Optional<String> running = PrintAgent.runningOn(config.agentPort());
        if (running.isEmpty()) {
            return false;
        }
        String server = running.get().isBlank() ? config.lastServer() : running.get();
        // Running but with no server yet means it is still being set up, so send this click to the
        // page where that is happening rather than opening a window on nothing.
        Browser.openApp(server.isBlank()
                ? "http://127.0.0.1:" + config.agentPort() + "/setup"
                : billingScreen(config, server));
        return true;
    }

    /**
     * The billing screen to open when a counter is already running on this machine.
     *
     * @return empty when none is running and this process should start one; the address to open
     *         when one is; or an empty string when one is running but has not reached a server yet
     */
    static Optional<String> windowOfRunningCounter(CounterConfig config) {
        return PrintAgent.runningOn(config.agentPort())
                .map(server -> server.isBlank() ? config.lastServer() : server)
                .map(server -> server.isBlank() ? "" : billingScreen(config, server));
    }

    /**
     * Starts the agent, moving up a port or two if this machine has something on the one it wants.
     * A counter with no agent has no printer and no setup page, so it is worth a few tries.
     */
    private static PrintAgent listening(CounterConfig config) {
        int wanted = config.agentPort();
        for (int port = wanted; port < wanted + PORTS_TO_TRY; port++) {
            config.agentPort(port);
            PrintAgent agent = new PrintAgent(config, Path.of(CounterConfig.folder().toString(), SPOOL));
            try {
                agent.start();
                return agent;
            } catch (IOException portTaken) {
                agent.close();
            }
        }
        config.agentPort(wanted);
        return null;
    }

    /**
     * The server this counter should use. Tries what is configured, and falls back to the one
     * that worked last time - a shop whose discovery is swallowed by a new access point should
     * not be stopped from billing while somebody works out why.
     */
    private static Optional<String> connect(CounterConfig config) {
        Optional<String> found = ServerFinder.find(config).map(ServerFinder.Found::url);
        if (found.isEmpty() && !config.lastServer().isBlank()) {
            found = ServerFinder.reachable(config.lastServer());
        }
        return found;
    }

    private static void remember(CounterConfig config, String server) {
        if (server.equals(config.lastServer())) {
            return;
        }
        config.lastServer(server);
        try {
            config.save();
        } catch (IOException cannotRemember) {
            // Not worth stopping for: the counter works, it just re-discovers next time.
            note("Could not remember where the server is: " + cannotRemember.getMessage());
        }
    }

    /**
     * The last place left to say something, for the few failures that leave no screen to say it
     * on. Everything a person is meant to read is on the setup page; this is for the engineer who
     * comes afterwards. The print agent writes here too, for a printer that fails with nothing to
     * say: a class name on a manager's screen is not a message, but it is worth keeping.
     */
    static void note(String what) {
        try {
            Path log = CounterConfig.folder().resolve("counter.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, Instant.now() + "  " + what + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException nowhereToWrite) {
            // Then there is genuinely nowhere left. Not worth a second failure on the way out.
        }
    }

    /**
     * The billing screen, told which of the shop's counters is looking at it. The screen keeps
     * that till's id and sends it once, at sign-in, which is how a bill gets this counter's
     * series. The number comes too, but only to be shown: what this machine is called is the
     * shop's to say.
     */
    static String billingScreen(CounterConfig config, String server) {
        return server + "/?counter=" + URLEncoder.encode(config.counterId(), StandardCharsets.UTF_8)
                + "&counterCode=" + URLEncoder.encode(config.code(), StandardCharsets.UTF_8)
                + "&agent=" + config.agentPort();
    }

    private static void keepRunning() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }
}
