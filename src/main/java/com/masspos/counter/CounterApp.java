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

    /**
     * Naming the three things that are ever actually wrong, in the order worth checking.
     *
     * <p>"Nothing answered" was true and useless: a counter cannot tell a server that is switched
     * off from one that is running but keeping to itself, or from a network that will not carry
     * the question. Two of the three are on the <em>server's</em> machine, which is the part
     * nobody guesses, so the message says so rather than sending somebody to look at the cable.
     */
    private static final String CANNOT_REACH = """
            This counter could not find the shop's server.

            On the server's computer, in order:

            1. Is GO RockIt running, and set to serve counters? Open its data folder, open
               pos.properties, and check pos.clients.max is the number of counters the shop
               has. 0 means the server keeps to itself. Restart it after changing this.

            2. Did Windows Firewall ask about GO RockIt, and was it allowed? Refused once,
               Windows remembers, and no counter will ever reach it.

            3. Is the network set to Private in Windows rather than Public? Windows stops
               machines reaching each other on a Public network whatever the firewall says.
               Both computers need this.

            If all three are right, type the server's address here instead of looking for it:
            some networks do not pass the question on.""";

    private CounterApp() {
    }

    public static void main(String[] args) {
        try {
            CounterConfig config = CounterConfig.load();
            if (openOnTheCounterAlreadyRunning(config)) {
                return;
            }
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
     * One counter process per machine.
     *
     * <p>Clicking the shortcut again used to start a whole second copy of this program. Only one
     * of them could hold the printer - the loser said so in a window and then carried on running
     * anyway - so a shop that opens the till two or three times a day finished the week with a row
     * of them in Task Manager, each with its own JVM. Now the new process asks the one already
     * running where it is billing, opens a window there, and stops.
     *
     * @return true when a counter was already running and the billing screen has been opened on it
     */
    private static boolean openOnTheCounterAlreadyRunning(CounterConfig config) throws IOException {
        Optional<String> window = windowOfRunningCounter(config);
        if (window.isEmpty()) {
            return false;
        }
        if (window.get().isEmpty()) {
            // It is ours and it is up, but it has not reached a server yet - the settings window
            // on the first copy is probably still open. Opening a second window on nothing would
            // help nobody, so say where to look.
            CounterSettings.say("GO RockIt counter",
                    "This counter is already running and is still being set up.\n\n"
                            + "Finish in the settings window that is already open.");
            return true;
        }
        Browser.openApp(window.get());
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
            CounterSettings.say("GO RockIt counter", CANNOT_REACH);
        }
        return found;
    }

    private static void start(CounterConfig config, String server) throws IOException {
        remember(config, server);
        PrintAgent agent = new PrintAgent(config, Path.of(CounterConfig.folder().toString(), SPOOL));
        agent.serving(server);
        try {
            agent.start();
        } catch (IOException portTaken) {
            CounterSettings.say("GO RockIt counter",
                    "Bills will not print on this counter: port " + agent.port() + " is already in use.\n\n"
                            + "Another program on this machine is using it. Change the print agent port in"
                            + " counter.properties, or close whatever has taken it, and start again.");
        }
        Browser.openApp(billingScreen(config, server));
        // The agent is the reason this process stays alive: the browser window is the shop's, but
        // the printer belongs to this program, and closing it would take the printer with it.
        //
        // It is also how the sign-in screen gets this window back. A counter the server turns away
        // - no place left in the shop, a number that is already the server's - has nothing useful
        // to do on a sign-in screen, and the page cannot open a window of this program's by itself.
        serveUntilClosed(config, agent);
    }

    /**
     * Waits for work, which on a counter means one of two things: nothing at all, which is the
     * normal day, or the page asking for the settings window back after the server refused this
     * machine. Settings saved, it looks for the server again and opens billing on it.
     */
    private static void serveUntilClosed(CounterConfig config, PrintAgent agent) {
        Optional<String> asked = agent.awaitSettingsRequest();
        while (asked.isPresent()) {
            if (CounterSettings.edit(config, asked.get().trim()).isPresent()) {
                Optional<String> server = connect(config);
                if (server.isPresent()) {
                    remember(config, server.get());
                    agent.serving(server.get());
                    openQuietly(config, server.get());
                }
            }
            asked = agent.awaitSettingsRequest();
        }
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
            CounterSettings.say("GO RockIt counter",
                    "Could not remember where the server is: " + cannotRemember.getMessage());
        }
    }

    private static void openQuietly(CounterConfig config, String server) {
        try {
            Browser.openApp(billingScreen(config, server));
        } catch (IOException noBrowser) {
            CounterSettings.say("GO RockIt counter",
                    "Could not open the billing screen: " + noBrowser.getMessage());
        }
    }

    /**
     * The billing screen, told which counter is looking at it. The screen keeps the number and
     * sends it with everything it asks the server, which is how a bill gets this counter's series.
     */
    static String billingScreen(CounterConfig config, String server) {
        return server + "/?counter=" + URLEncoder.encode(config.counterId(), StandardCharsets.UTF_8)
                + "&counterCode=" + URLEncoder.encode(config.code(), StandardCharsets.UTF_8)
                + "&agent=" + config.agentPort();
    }

}
