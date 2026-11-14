package com.masspos.hardware;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * Raw ESC/POS over an RS-232 port or a USB printer's virtual COM port, via jSerialComm: no OS
 * spooler or printer driver involved. The port stays open between jobs; if a write fails (unplugged,
 * powered off) the port is closed and simply reopened by the next job.
 */
public class SerialPrinterConnection implements PrinterConnection {

    private static final Logger log = LoggerFactory.getLogger(SerialPrinterConnection.class);
    private static final int DEFAULT_READ_TIMEOUT_MS = 500;

    private final PrinterProperties.Serial settings;
    private final int writeTimeoutMs;
    private SerialPort port;

    public SerialPrinterConnection(PrinterProperties.Serial settings, Duration writeTimeout) {
        this.settings = settings;
        this.writeTimeoutMs = (int) writeTimeout.toMillis();
    }

    @Override
    public void write(EscPos document) {
        byte[] data = document.toBytes();
        SerialPort serial = open();
        int written = 0;
        while (written < data.length) {
            int n = serial.writeBytes(data, data.length - written, written);
            if (n <= 0) {
                close();
                throw new PrinterException("The printer on %s stopped part way through the bill. Check it is switched on, has paper, and its cable is in. (%d of %d sent.)"
                        .formatted(describe(), written, data.length));
            }
            written += n;
        }
    }

    @Override
    public OptionalInt query(byte[] request, Duration timeout) {
        SerialPort serial = open();
        // Drain stale input only. flushIOBuffers() would also discard the unsent tail of the last job.
        byte[] stale = new byte[64];
        while (serial.bytesAvailable() > 0 && serial.readBytes(stale, stale.length) > 0) {
            // discard
        }
        serial.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING | SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                (int) timeout.toMillis(), writeTimeoutMs);
        if (serial.writeBytes(request, request.length) != request.length) {
            close();
            return OptionalInt.empty();
        }
        byte[] response = new byte[1];
        return serial.readBytes(response, 1) == 1 ? OptionalInt.of(response[0] & 0xFF) : OptionalInt.empty();
    }

    @Override
    public String describe() {
        return settings.port() == null || settings.port().isBlank() ? "(no serial port configured)" : settings.port();
    }

    @Override
    public void close() {
        if (port != null) {
            port.closePort();
            port = null;
        }
    }

    private SerialPort open() {
        if (port != null && port.isOpen()) {
            return port;
        }
        if (settings.port() == null || settings.port().isBlank()) {
            throw new PrinterException("No COM port has been chosen for this printer yet."
                    + " Choose one under Settings, Printer: the ports on this machine are listed there.");
        }
        SerialPort candidate;
        try {
            candidate = SerialPort.getCommPort(settings.port());
        } catch (SerialPortInvalidPortException e) {
            throw new PrinterException("The printer is not on " + settings.port() + ". Check it is switched on and plugged in, or choose the right port under Settings, Printer.", e);
        }
        candidate.setComPortParameters(settings.baudRate(), 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        candidate.setFlowControl(flowControlMask(settings.flowControl()));
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING | SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                DEFAULT_READ_TIMEOUT_MS, writeTimeoutMs);
        if (!candidate.openPort()) {
            throw new PrinterException("The printer on " + settings.port() + " could not be opened."
                    + " Check it is switched on, plugged in, and not in use by another program.");
        }
        log.info("Opened printer port {} at {} baud, flow control {}", settings.port(), settings.baudRate(),
                settings.flowControl());
        port = candidate;
        return port;
    }

    private static int flowControlMask(PrinterProperties.FlowControl flowControl) {
        return switch (flowControl) {
            case NONE -> SerialPort.FLOW_CONTROL_DISABLED;
            case RTS_CTS -> SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case DTR_DSR -> SerialPort.FLOW_CONTROL_DTR_ENABLED | SerialPort.FLOW_CONTROL_DSR_ENABLED;
            case XON_XOFF -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
        };
    }
}
