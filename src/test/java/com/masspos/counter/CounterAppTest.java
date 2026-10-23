package com.masspos.counter;

import com.masspos.hardware.EscPos;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
     * The counter number travels on the address, which is the only way the page can learn which
     * machine it is open on. It has to survive a name with a space in it.
     */
    @Test
    void tellsTheBillingScreenWhichCounterIsLookingAtIt() {
        CounterConfig config = new CounterConfig();
        config.code("t2");
        config.name("Counter 2");

        String url = CounterApp.billingScreen(config, "http://192.168.1.10:8765");

        assertThat(url).isEqualTo("http://192.168.1.10:8765/?counter=T2&counterName=Counter+2&agent=8767");
    }

    /** A counter with nothing filled in asks rather than guessing at a server. */
    @Test
    void willNotTryToConnectUntilItKnowsWhichCounterItIs() {
        CounterConfig config = new CounterConfig();
        assertThat(config.readyToConnect()).isFalse();

        config.code("T2");
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
