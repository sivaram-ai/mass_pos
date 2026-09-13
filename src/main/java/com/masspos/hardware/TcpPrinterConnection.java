package com.masspos.hardware;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.OptionalInt;

/**
 * Raw ESC/POS to a LAN printer's raw port (normally 9100). Connects per job: network printers
 * commonly accept a single client and drop idle sockets.
 */
public class TcpPrinterConnection implements PrinterConnection {

    private final String host;
    private final int port;
    private final int timeoutMs;

    public TcpPrinterConnection(PrinterProperties.Tcp settings, Duration timeout) {
        this.host = settings.host();
        this.port = settings.port();
        this.timeoutMs = (int) timeout.toMillis();
    }

    @Override
    public void write(EscPos document) {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(document.toBytes());
            out.flush();
        } catch (IOException e) {
            throw new PrinterException("Cannot print to " + describe() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public OptionalInt query(byte[] request, Duration timeout) {
        Socket socket;
        try {
            socket = connect();
        } catch (IOException e) {
            throw new PrinterException("Cannot reach printer at " + describe() + ": " + e.getMessage(), e);
        }
        try (socket) {
            socket.setSoTimeout((int) timeout.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            InputStream in = socket.getInputStream();
            int response = in.read();
            return response < 0 ? OptionalInt.empty() : OptionalInt.of(response);
        } catch (IOException e) {
            return OptionalInt.empty(); // connected, but the printer did not answer
        }
    }

    @Override
    public String describe() {
        return host + ":" + port;
    }

    @Override
    public void close() {
        // Nothing held between jobs.
    }

    private Socket connect() throws IOException {
        if (host == null || host.isBlank()) {
            throw new PrinterException("No printer host configured: set pos.printer.tcp.host");
        }
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }
}
