package com.masspos.hardware;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SerialPrinterConnectionTest {

    @Test
    void unconfiguredPortSaysWhatToSet() {
        SerialPrinterConnection connection = connection("");

        assertThatThrownBy(() -> connection.write(new EscPos(CodePage.PC437, 32)))
                .isInstanceOf(PrinterException.class)
                .hasMessageContaining("pos.printer.serial.port");
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
