package com.masspos.counter;

import com.masspos.desktop.Browser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What the shortcut on a counter runs.
 *
 * <p>A counter is not a till with the database taken out - it is a different, much smaller
 * program. It holds no shop data, needs no licence and keeps nothing a shop would miss: its whole
 * state is one properties file. What it does have is the two things a browser window cannot do by
 * itself - find the shop's server, and drive the printer and cash drawer standing on this counter.
 *
 * <p>In order: read the settings, ask for them if they are not there or the server cannot be
 * found, start the print agent, and open the billing screen pointed at the server with this
 * counter's number on the address. The server reads that number once, at sign-in, and every bill
 * taken here is numbered as this counter from then on.
 */
public final class CounterApp {

    /** Where FILE-type jobs land, for a counter set up before its printer has arrived. */
    private static final String SPOOL = "printer-out";

    private CounterApp() {
    }

    public static void main(String[] args) {
        try {
            CounterConfig config = CounterConfig.load();
            if (!config.readyToConnect() && settingsCancelled(config)) {
                return;
            }
            Optional<String> server = connect(config);
            while (server.isEmpty()) {
                if (settingsCancelled(config)) {
                    return;
                }
                server = connect(config);
            }
            start(config, server.get());
        } catch (Exception unexpected) {
            // Nobody is watching a console on a counter, so anything that escapes gets a window.
            CounterSettings.say("GO RockIt counter", "This counter could not start: " + unexpected);
        }
    }

    /**
     * Opens the settings window, re-asking after a failed connection with the reason on screen.
     *
     * @return true when the person closed it without saving, which means they want to stop
     */
    private static boolean settingsCancelled(CounterConfig config) {
        return CounterSettings.edit(config).isEmpty();
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
        if (found.isEmpty()) {
            CounterSettings.say("GO RockIt counter",
                    "This counter could not reach the shop's server.\n\n"
                            + "Check that the server computer is on and that this machine is on the same\n"
                            + "network - a cable or Wi-Fi, either is fine - then try again.");
        }
        return found;
    }

    private static void start(CounterConfig config, String server) throws IOException {
        if (!server.equals(config.lastServer())) {
            config.lastServer(server);
            try {
                config.save();
            } catch (IOException cannotRemember) {
                // Not worth stopping for: the counter works, it just re-discovers next time.
                CounterSettings.say("GO RockIt counter",
                        "Could not remember where the server is: " + cannotRemember.getMessage());
            }
        }
        PrintAgent agent = new PrintAgent(config, Path.of(CounterConfig.folder().toString(), SPOOL));
        agent.serving(server);
        try {
            agent.start();
        } catch (IOException portTaken) {
            CounterSettings.say("GO RockIt counter",
                    "Bills will not print on this counter: port " + agent.port() + " is already in use.\n\n"
                            + "Another copy of the counter app is probably running. Close it and start again.");
        }
        Browser.openApp(billingScreen(config, server));
        // The agent is the reason this process stays alive: the browser window is the shop's, but
        // the printer belongs to this program, and closing it would take the printer with it.
        keepRunning();
    }

    /**
     * The billing screen, told which counter is looking at it. The screen keeps the number and
     * sends it with everything it asks the server, which is how a bill gets this counter's series.
     */
    static String billingScreen(CounterConfig config, String server) {
        return server + "/?counter=" + URLEncoder.encode(config.code(), StandardCharsets.UTF_8)
                + "&counterName=" + URLEncoder.encode(config.name(), StandardCharsets.UTF_8)
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
