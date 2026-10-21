package com.masspos.hardware;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WindowsPrinterConnectionTest {

    private static final String ABSENT = "No Such Printer 9000";

    @Test
    void sayingWhichPrinterIsMissingAndWhatIsInstalled() {
        EscPos receipt = new EscPos(CodePage.PC437, 48).line("Item");

        assertThatThrownBy(() -> connection(ABSENT).write(receipt))
                .isInstanceOf(PrinterException.class)
                .hasMessageContaining(ABSENT)
                .hasMessageContaining("installed");
    }

    /**
     * A drawer kick is a job with no printed lines. A driver printer has no drawer port, so it must
     * not answer one with a blank sheet of paper - and must not fail either.
     */
    @Test
    void aJobWithNothingToPrintIsDropped() {
        EscPos kick = new EscPos(CodePage.PC437, 48).openDrawer(EscPos.DrawerPin.PIN_2, Duration.ofMillis(100));

        assertThatCode(() -> connection(ABSENT).write(kick)).doesNotThrowAnyException();
    }

    @Test
    void blankLinesAloneAreNotWorthPrinting() {
        EscPos nothing = new EscPos(CodePage.PC437, 48).feed(4);

        assertThatCode(() -> connection(ABSENT).write(nothing)).doesNotThrowAnyException();
    }

    @Test
    void namesItsTarget() {
        assertThat(connection("Canon G3080 series").describe()).isEqualTo("Canon G3080 series");
        assertThat(connection("").describe()).isEqualTo("the Windows default printer");
    }

    /** Driver printers answer no real-time status request: the status screen shows "not answering". */
    @Test
    void hasNoStatusToReport() {
        assertThat(connection(ABSENT).query(EscPos.statusRequest(1), Duration.ofMillis(10))).isEmpty();
    }

    /** A4 is nearly three times an 80 mm roll: a bill printed across all of it would look absurd. */
    @Test
    void keepsTheReceiptAsNarrowAsARollOnWiderPaper() {
        assertThat(WindowsPrinterConnection.printWidth(538, 80)).isCloseTo(226.77, within(0.01));
        assertThat(WindowsPrinterConnection.printWidth(200, 80)).isEqualTo(200);
        assertThat(WindowsPrinterConnection.printWidth(538, 0)).isEqualTo(538);
    }

    private static WindowsPrinterConnection connection(String name) {
        return new WindowsPrinterConnection(new PrinterProperties.Windows(name, 80));
    }
}
