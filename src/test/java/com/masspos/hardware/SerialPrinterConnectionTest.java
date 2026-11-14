package com.masspos.hardware;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SerialPrinterConnectionTest {

    @Test
    void unconfiguredPortSaysWhatToSet() {
        SerialPrinterConnection connection = connection("");

        // What a shop can act on, and nothing it cannot: the screen to open, not the property to
        // edit or the endpoint that lists the ports.
        assertThatThrownBy(() -> connection.write(new EscPos(CodePage.PC437, 32)))
                .isInstanceOf(PrinterException.class)
                .hasMessageContaining("Choose one under Settings, Printer")
                .hasMessageNotContaining("pos.printer")
                .hasMessageNotContaining("/api/");
    }

    @Test
    void missingPortFailsCleanly() {
        SerialPrinterConnection connection = connection("COM_MASSPOS_MISSING");

        assertThatThrownBy(() -> connection.write(new EscPos(CodePage.PC437, 32)))
                .isInstanceOf(PrinterException.class)
                .hasMessageContaining("COM_MASSPOS_MISSING");
    }

    private static SerialPrinterConnection connection(String port) {
        return new SerialPrinterConnection(
                new PrinterProperties.Serial(port, 9600, PrinterProperties.FlowControl.NONE), Duration.ofSeconds(1));
    }
}
