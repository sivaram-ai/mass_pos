package com.masspos.hardware;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterConnectionsTest {

    private static final Path OUT = Path.of("target", "test-data", "printer-connections");

    @Test
    void buildsTheConnectionTheChosenTypeAsksFor() {
        assertThat(PrinterConnections.connect(printer(PrinterProperties.Type.SERIAL), OUT))
                .isInstanceOf(SerialPrinterConnection.class);
        assertThat(PrinterConnections.connect(printer(PrinterProperties.Type.TCP), OUT))
                .isInstanceOf(TcpPrinterConnection.class);
        assertThat(PrinterConnections.connect(printer(PrinterProperties.Type.WINDOWS), OUT))
                .isInstanceOf(WindowsPrinterConnection.class);
        assertThat(PrinterConnections.connect(printer(PrinterProperties.Type.FILE), OUT))
                .isInstanceOf(FilePrinterConnection.class);
    }

    /** Where bills are going has to be answerable on a dead printer: it is what the screen shows. */
    @Test
    void saysWhereJobsGoWithoutTouchingTheHardware() {
        assertThat(PrinterConnections.describe(printer(PrinterProperties.Type.SERIAL), OUT)).isEqualTo("COM7");
        assertThat(PrinterConnections.describe(printer(PrinterProperties.Type.TCP), OUT))
                .isEqualTo("192.168.1.50:9100");
        assertThat(PrinterConnections.describe(printer(PrinterProperties.Type.WINDOWS), OUT))
                .isEqualTo("Canon G3080 series");
        assertThat(PrinterConnections.describe(printer(PrinterProperties.Type.FILE), OUT))
                .isEqualTo(OUT.toAbsolutePath().toString());
    }

    @Test
    void saysSoWhenTheTypeIsChosenButNotYetSetUp() {
        PrinterProperties blank = new PrinterProperties(PrinterProperties.Type.SERIAL,
                new PrinterProperties.Serial("", 9600, PrinterProperties.FlowControl.NONE),
                new PrinterProperties.Tcp("", 9100), new PrinterProperties.Windows("", 80),
                48, CodePage.PC437, EscPos.DrawerPin.PIN_2, Duration.ofMillis(100), Duration.ofSeconds(5));

        assertThat(PrinterConnections.describe(blank, OUT)).isEqualTo("no COM port chosen");
        assertThat(PrinterConnections.describe(withType(blank, PrinterProperties.Type.TCP), OUT))
                .isEqualTo("no address set");
        assertThat(PrinterConnections.describe(withType(blank, PrinterProperties.Type.WINDOWS), OUT))
                .isEqualTo("the Windows default printer");
    }

    /** Settings are a record, so "has the printer changed?" is one equals call. */
    @Test
    void twoIdenticalSetupsAreTheSamePrinter() {
        assertThat(printer(PrinterProperties.Type.TCP)).isEqualTo(printer(PrinterProperties.Type.TCP));
        assertThat(printer(PrinterProperties.Type.TCP)).isNotEqualTo(printer(PrinterProperties.Type.SERIAL));
    }

    private static PrinterProperties printer(PrinterProperties.Type type) {
        return new PrinterProperties(type,
                new PrinterProperties.Serial("COM7", 9600, PrinterProperties.FlowControl.NONE),
                new PrinterProperties.Tcp("192.168.1.50", 9100),
                new PrinterProperties.Windows("Canon G3080 series", 80),
                48, CodePage.PC437, EscPos.DrawerPin.PIN_2, Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    private static PrinterProperties withType(PrinterProperties printer, PrinterProperties.Type type) {
        return new PrinterProperties(type, printer.serial(), printer.tcp(), printer.windows(), printer.columns(),
                printer.codePage(), printer.drawerPin(), printer.drawerPulse(), printer.timeout());
    }
}
