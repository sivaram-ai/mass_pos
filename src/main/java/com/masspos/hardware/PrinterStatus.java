package com.masspos.hardware;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * Printer state decoded from DLE EOT replies. {@code responding} is false when the port or host is
 * open but nothing answers: printer powered off, the FILE printer, or a model without real-time
 * status. The other flags then mean nothing. A port or host that cannot be opened is not a
 * status; that throws {@link PrinterException}.
 */
public record PrinterStatus(boolean responding, boolean online, boolean coverOpen, boolean paperNearEnd,
                            boolean paperOut, boolean error) {

    public static final PrinterStatus NOT_RESPONDING = new PrinterStatus(false, false, false, false, false, false);

    /** A reply with no flags raised: bits 1 and 4 are always set. */
    private static final int NO_FLAGS = 0x12;

    static PrinterStatus query(PrinterConnection connection, Duration timeout) {
        OptionalInt printer = connection.query(EscPos.statusRequest(1), timeout);
        if (printer.isEmpty() || !isStatusByte(printer.getAsInt())) {
            return NOT_RESPONDING;
        }
        int offlineCause = statusByte(connection.query(EscPos.statusRequest(2), timeout));
        int paperSensor = statusByte(connection.query(EscPos.statusRequest(4), timeout));
        return decode(printer.getAsInt(), offlineCause, paperSensor);
    }

    static PrinterStatus decode(int printer, int offlineCause, int paperSensor) {
        boolean paperStoppedPrinting = (offlineCause & 0x20) != 0;
        return new PrinterStatus(
                true,
                (printer & 0x08) == 0,
                (offlineCause & 0x04) != 0,
                (paperSensor & 0x0C) != 0,
                (paperSensor & 0x60) != 0 || paperStoppedPrinting,
                (offlineCause & 0x40) != 0);
    }

    /** Every DLE EOT reply has bits 1 and 4 set and bits 0 and 7 clear; anything else is line noise. */
    static boolean isStatusByte(int b) {
        return (b & 0b1001_0011) == 0b0001_0010;
    }

    private static int statusByte(OptionalInt reply) {
        return reply.isPresent() && isStatusByte(reply.getAsInt()) ? reply.getAsInt() : NO_FLAGS;
    }
}
