package com.masspos.hardware;

import java.time.Duration;
import java.util.OptionalInt;

/** Transport to one receipt printer. Only ever called from {@link ThermalPrinter}'s worker thread. */
public interface PrinterConnection extends AutoCloseable {

    /** Sends a complete job. Throws {@link PrinterException}; never retries, as that could print twice. */
    void write(EscPos document);

    /**
     * Sends a real-time request and returns the single response byte, or empty when the transport
     * cannot read or the printer does not answer within the timeout. Throws {@link PrinterException}
     * when the port or host cannot be opened at all, so callers can tell a configuration or
     * cabling problem from a silent printer.
     */
    OptionalInt query(byte[] request, Duration timeout);

    /** Target for logs and error messages, e.g. "COM3" or "192.168.1.50:9100". */
    String describe();

    @Override
    void close();
}
