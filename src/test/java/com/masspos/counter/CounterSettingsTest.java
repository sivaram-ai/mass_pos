package com.masspos.counter;

import com.masspos.hardware.PrinterProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the counter's settings window will and will not let somebody save.
 *
 * <p>"Save and start billing" is a promise that this machine can start billing, and a promise this
 * window cannot keep ends with somebody standing at a sign-in screen that turns them away. So the
 * button is off until every one of these is answered. Only what the machine can judge by itself is
 * here; whether the shop has a place left is the server's answer, asked over the network.
 */
class CounterSettingsTest {

    @TempDir
    Path folder;

    @Test
    void wantsACounterNumberThatCouldGoOnABill() {
        assertThat(CounterSettings.problemWith(counter(settings -> settings.code(""))))
                .contains("Give this counter a number");
        assertThat(CounterSettings.problemWith(counter(settings -> settings.code("COUNTER-TWO"))))
                .contains("not a usable counter number");
        assertThat(CounterSettings.problemWith(counter(settings -> settings.code("2T"))))
                .contains("not a usable counter number");
    }

    /** The name is what staff read on every screen in the shop, so it is asked for, not guessed. */
    @Test
    void wantsANameSomebodyChose() {
        assertThat(CounterSettings.problemWith(counter(settings -> settings.name(""))))
                .contains("Give this counter a name");
        assertThat(CounterSettings.problemWith(counter(settings -> settings.name("  ")))).isNotNull();
    }

    @Test
    void wantsSomewhereToLookForTheServer() {
        assertThat(CounterSettings.problemWith(counter(settings -> {
            settings.find(CounterConfig.Find.ADDRESS);
            settings.address("");
        }))).contains("Type the server's name or IP address");
        assertThat(CounterSettings.problemWith(counter(settings -> {
            settings.find(CounterConfig.Find.URL);
            settings.url("");
        }))).contains("full address");
        // Finding it on the network needs nothing typed at all, which is the usual case.
        assertThat(CounterSettings.problemWith(counter(settings -> settings.find(CounterConfig.Find.AUTOMATIC))))
                .isNull();
    }

    /** The same ranges the agent holds a saved printer to, asked before anything is written. */
    @Test
    void wantsAPrinterThatCouldBeOpened() {
        assertThat(CounterSettings.problemWith(counter(settings -> {
            settings.printerType(PrinterProperties.Type.SERIAL);
            settings.serialPort("");
        }))).contains("COM port");
        assertThat(CounterSettings.problemWith(counter(settings -> {
            settings.printerType(PrinterProperties.Type.TCP);
            settings.tcpHost("");
        }))).contains("network printer");
        assertThat(CounterSettings.problemWith(counter(settings -> {
            settings.printerType(PrinterProperties.Type.TCP);
            settings.tcpHost("192.168.1.90");
            settings.tcpPort(0);
        }))).contains("between 1 and 65535");
        assertThat(CounterSettings.problemWith(counter(settings -> settings.columns(4))))
                .contains("Characters per line");
        assertThat(CounterSettings.problemWith(counter(settings -> settings.columns(200)))).isNotNull();
    }

    /** A counter that is set up properly has nothing standing in its way here. */
    @Test
    void letsAFinishedCounterThrough() {
        assertThat(CounterSettings.problemWith(counter(settings -> {
        }))).isNull();
        assertThat(CounterSettings.problemWith(counter(settings -> {
            settings.find(CounterConfig.Find.ADDRESS);
            settings.address("192.168.1.10");
        }))).isNull();
    }

    /** A counter as somebody would have filled it in, with one thing changed per test. */
    private CounterConfig counter(java.util.function.Consumer<CounterConfig> change) {
        CounterConfig config = CounterConfig.loadFrom(folder);
        config.code("T2");
        config.name("Counter 2");
        config.find(CounterConfig.Find.AUTOMATIC);
        config.printerType(PrinterProperties.Type.FILE);
        config.columns(48);
        config.tcpPort(9100);
        change.accept(config);
        return config;
    }
}
