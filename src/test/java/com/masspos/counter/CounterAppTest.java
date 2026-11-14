package com.masspos.counter;

import com.masspos.hardware.EscPos;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The counter application, which is the half of the shop that has no database to check itself
 * against. Everything here is what happens before anyone can sign in, so it has to be right
 * without a server to correct it.
 */
class CounterAppTest {

    /** A shop types what it knows: an address, a name, sometimes a port. All three must work. */
    @Test
    void turnsWhateverTheShopTypedIntoAnAddressToTry() {
        assertThat(ServerFinder.urlFor("192.168.1.10")).isEqualTo("http://192.168.1.10:8765");
        assertThat(ServerFinder.urlFor("backoffice")).isEqualTo("http://backoffice:8765");
        assertThat(ServerFinder.urlFor("192.168.1.10:9000")).isEqualTo("http://192.168.1.10:9000");
        assertThat(ServerFinder.urlFor("http://till.shop.local:8765/")).isEqualTo("http://till.shop.local:8765");
        assertThat(ServerFinder.urlFor("  ")).isEmpty();
    }

    /** One join must never make two slashes, however the address was typed. */
    @Test
    void neverBuildsAnAddressWithTwoSlashesInIt() {
        assertThat(ServerFinder.normalise("http://a.b:8765///")).isEqualTo("http://a.b:8765");
        assertThat(ServerFinder.normalise(null)).isEmpty();
    }

    /**
     * Which of the shop's counters this machine is travels on the address, as the till's id: that
     * is the only way the page can learn which counter it is open on, and it is an id because the
     * number and the name belong to the shop's own list, not to this machine.
     */
    @Test
    void tellsTheBillingScreenWhichCounterIsLookingAtIt() {
        CounterConfig config = new CounterConfig();
        config.counter("01a1cb62-d9d7-75cd-be17-fa6db596caa3", "t2", "Counter 2");

        String url = CounterApp.billingScreen(config, "http://192.168.1.10:8765");

        assertThat(url).isEqualTo("http://192.168.1.10:8765/?counter=01a1cb62-d9d7-75cd-be17-fa6db596caa3"
                + "&counterCode=T2&agent=8767");
    }

    /**
     * One counter process per machine. Clicking the shortcut again used to start a second copy of
     * the whole program: only one of them could hold the printer, but both stayed alive, so a shop
     * that opens the till a few times a day ended the week with a row of them in Task Manager.
     * Now the second click finds the first and opens a window on the server it is already using.
     */
    @Test
    void opensAWindowOnTheCounterAlreadyRunningInsteadOfStartingASecond() throws Exception {
        CounterConfig config = new CounterConfig();
        config.counter("01a1cb62-d9d7-75cd-be17-fa6db596caa3", "T2", "Counter 2");
        config.agentPort(freePort());

        assertThat(CounterApp.windowOfRunningCounter(config))
                .as("nothing running here, so this process starts the counter itself").isEmpty();

        try (PrintAgent running = new PrintAgent(config, Path.of("target", "test-data", "counter-spool"))) {
            running.start();
            running.serving("http://192.168.1.10:8765");

            assertThat(CounterApp.windowOfRunningCounter(config))
                    .contains("http://192.168.1.10:8765/?counter=01a1cb62-d9d7-75cd-be17-fa6db596caa3"
                            + "&counterCode=T2&agent=" + config.agentPort());
        }
    }

    /** A counter that is up but still being set up has no billing screen to open a window on. */
    @Test
    void saysSoWhenTheCounterAlreadyRunningHasNotReachedAServerYet() throws Exception {
        CounterConfig config = new CounterConfig();
        config.counter("01a1cb62-d9d7-75cd-be17-fa6db596caa3", "T2", "Counter 2");
        config.agentPort(freePort());

        try (PrintAgent running = new PrintAgent(config, Path.of("target", "test-data", "counter-spool"))) {
            running.start();

            assertThat(CounterApp.windowOfRunningCounter(config)).contains("");
        }
    }

    /**
     * A shop that is not answering is not somewhere to send a browser. It shows the browser's own
     * error page - a cloud, a Refresh button, and nothing of this program at all - and from there
     * a counter pointed at an address that has stopped working cannot be put right without closing
     * the program. The click goes to this machine's own setup page instead, which is served from
     * here and answers whatever the network is doing.
     */
    @Test
    void sendsAClickToTheSetupPageRatherThanToAShopThatIsNotAnswering() throws Exception {
        CounterConfig config = new CounterConfig();
        config.counter("01a1cb62-d9d7-75cd-be17-fa6db596caa3", "T2", "Counter 2");
        config.agentPort(freePort());

        // A port on this machine that nothing is listening on: refused at once, and it cannot be
        // answered by whatever happens to be on the network the build is running on.
        assertThat(CounterApp.windowFor(config, "http://127.0.0.1:" + freePort()))
                .isEqualTo("http://127.0.0.1:" + config.agentPort() + "/setup");
        assertThat(CounterApp.windowFor(config, ""))
                .as("still being set up, so there is no shop to open a window on")
                .isEqualTo("http://127.0.0.1:" + config.agentPort() + "/setup");
    }

    /** And when it is answering, straight to billing, which is the whole point of the shortcut. */
    @Test
    void sendsAClickToBillingWhenTheShopIsThere() throws Exception {
        CounterConfig config = new CounterConfig();
        config.counter("01a1cb62-d9d7-75cd-be17-fa6db596caa3", "T2", "Counter 2");
        config.agentPort(freePort());

        try (java.net.ServerSocket shop = new java.net.ServerSocket(0)) {
            String url = "http://127.0.0.1:" + shop.getLocalPort();

            assertThat(CounterApp.windowFor(config, url))
                    .isEqualTo(url + "/?counter=01a1cb62-d9d7-75cd-be17-fa6db596caa3"
                            + "&counterCode=T2&agent=" + config.agentPort());
        }
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A counter that has not been told which till it is asks rather than guessing at a server. */
    @Test
    void willNotTryToConnectUntilItKnowsWhichCounterItIs() {
        CounterConfig config = new CounterConfig();
        assertThat(config.readyToConnect()).isFalse();

        config.counter("01a1cb62-d9d7-75cd-be17-fa6db596caa3", "T2", "Counter 2");
        assertThat(config.readyToConnect()).as("finding the server on the network needs nothing typed").isTrue();

        config.find(CounterConfig.Find.ADDRESS);
        assertThat(config.readyToConnect()).as("but an address does").isFalse();
        config.address("192.168.1.10");
        assertThat(config.readyToConnect()).isTrue();
    }

    /** A bill arrives in both shapes, because the counter's printer might want either. */
    @Test
    void readsABillInTheTwoShapesAPrinterMightWantIt() {
        byte[] escpos = {0x1B, 0x40, 'h', 'i'};
        String body = """
                {"escpos":"%s","lines":[
                  {"text":"MASS MART","align":"CENTER","bold":true,"doubleWidth":true,"doubleHeight":true},
                  {"text":"Bill No: 00004","align":"LEFT"}]}
                """.formatted(Base64.getEncoder().encodeToString(escpos));

        PrintAgent.Job job = PrintJobs.read(body.getBytes(StandardCharsets.UTF_8));

        assertThat(job.bytes()).isEqualTo(escpos);
        assertThat(job.lines()).hasSize(2);
        assertThat(job.lines().get(0).text()).isEqualTo("MASS MART");
        assertThat(job.lines().get(0).align()).isEqualTo(EscPos.Align.CENTER);
        assertThat(job.lines().get(0).bold()).isTrue();
        assertThat(job.lines().get(1).doubleWidth()).isFalse();
    }

    /** Anything else that reaches the port is refused rather than sent to the printer. */
    @Test
    void refusesAnythingThatIsNotABill() {
        assertThatThrownBy(() -> PrintJobs.read("hello".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> PrintJobs.read("{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing on it");
    }

    /**
     * A job the server laid out has to reach both kinds of printer: the bytes a thermal printer
     * executes and the lines a driver printer renders. Losing either would leave one kind of
     * counter printing blank paper.
     */
    @Test
    void aRenderedJobCarriesBytesForOnePrinterAndLinesForTheOther() {
        EscPos original = new EscPos(com.masspos.hardware.CodePage.PC437, 48);
        original.bold(true).line("MASS MART").bold(false).line("Bill No: 00004");

        EscPos travelled = EscPos.rendered(original.toBytes(), original.printedLines());

        assertThat(travelled.toBytes()).isEqualTo(original.toBytes());
        assertThat(travelled.printedLines()).isEqualTo(original.printedLines());
        assertThat(travelled.plainText()).contains("MASS MART", "Bill No: 00004");
    }
}
