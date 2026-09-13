package com.masspos.hardware;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterStatusTest {

    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @Test
    void healthyPrinter() {
        assertThat(PrinterStatus.decode(0x12, 0x12, 0x12))
                .isEqualTo(new PrinterStatus(true, true, false, false, false, false));
    }

    @Test
    void offlineWithCoverOpenAndPaperOut() {
        assertThat(PrinterStatus.decode(0x1A, 0x36, 0x72))
                .isEqualTo(new PrinterStatus(true, false, true, false, true, false));
    }

    @Test
    void paperNearEnd() {
        assertThat(PrinterStatus.decode(0x12, 0x12, 0x1E).paperNearEnd()).isTrue();
    }

    @Test
    void asksPrinterThenOfflineCauseThenPaperSensor() {
        List<Integer> asked = new ArrayList<>();

        assertThat(PrinterStatus.query(replying(asked, OptionalInt.of(0x12)), TIMEOUT).responding()).isTrue();
        assertThat(asked).containsExactly(1, 2, 4);
    }

    @Test
    void silentPrinterIsNotResponding() {
        assertThat(PrinterStatus.query(replying(new ArrayList<>(), OptionalInt.empty()), TIMEOUT))
                .isEqualTo(PrinterStatus.NOT_RESPONDING);
    }

    @Test
    void lineNoiseIsNotAStatus() {
        assertThat(PrinterStatus.query(replying(new ArrayList<>(), OptionalInt.of(0xFF)), TIMEOUT))
                .isEqualTo(PrinterStatus.NOT_RESPONDING);
    }

    private static PrinterConnection replying(List<Integer> asked, OptionalInt reply) {
        return new PrinterConnection() {
            @Override
            public void write(EscPos document) {
            }

            @Override
            public OptionalInt query(byte[] request, Duration timeout) {
                asked.add((int) request[2]);
                return reply;
            }

            @Override
            public String describe() {
                return "fake";
            }

            @Override
            public void close() {
            }
        };
    }
}
