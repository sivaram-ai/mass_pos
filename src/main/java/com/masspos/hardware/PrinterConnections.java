package com.masspos.hardware;

import com.masspos.config.PosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Holds the one connection to this till's printer and rebuilds it when the settings screen changes
 * the printer. Nothing here opens a port or a socket: a connection is built from settings and only
 * reaches the hardware when a job is sent, so a printer that is switched off, unplugged or
 * misconfigured can never stop the till starting or taking a bill.
 *
 * <p>Asked for a connection only from {@link ThermalPrinter}'s worker thread, which is what keeps a
 * printer swap from pulling the port out from under a job being written.
 */
@Service
public class PrinterConnections implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PrinterConnections.class);

    private final PrinterSettingsService settings;
    private final Path fileDirectory;
    private PrinterConnection open;
    private PrinterProperties openedFor;

    public PrinterConnections(PrinterSettingsService settings, PosProperties pos) {
        this.settings = settings;
        this.fileDirectory = pos.dataDir().resolve("printer-out");
    }

    /** The connection for the printer as it is set now, reconnecting if the settings have changed. */
    public synchronized PrinterConnection current() {
        PrinterProperties now = settings.active();
        if (open != null && now.equals(openedFor)) {
            return open;
        }
        closeOpen();
        if (openedFor != null) {
            log.info("Printer changed to {} {}", now.type(), describe(now, fileDirectory));
        }
        open = connect(now, fileDirectory);
        openedFor = now;
        return open;
    }

    /** Where jobs are going, for logs and for the message on screen. Opens nothing. */
    public String describe() {
        return describe(settings.active(), fileDirectory);
    }

    static PrinterConnection connect(PrinterProperties printer, Path fileDirectory) {
        return switch (printer.type()) {
            case SERIAL -> new SerialPrinterConnection(printer.serial(), printer.timeout());
            case TCP -> new TcpPrinterConnection(printer.tcp(), printer.timeout());
            case WINDOWS -> new WindowsPrinterConnection(printer.windows());
            case FILE -> new FilePrinterConnection(fileDirectory);
        };
    }

    static String describe(PrinterProperties printer, Path fileDirectory) {
        return describe(printer, fileDirectory, WindowsPrinterConnection::defaultPrinterName);
    }

    static String describe(PrinterProperties printer, Path fileDirectory, Supplier<String> windowsDefault) {
        return switch (printer.type()) {
            case SERIAL -> printer.serial().port().isBlank() ? "no COM port chosen" : printer.serial().port();
            case TCP -> printer.tcp().host().isBlank()
                    ? "no address set" : printer.tcp().host() + ":" + printer.tcp().port();
            // Named, not just "the default": a till printing into the wrong queue looks like
            // nothing at all is wrong until someone goes looking for the paper.
            case WINDOWS -> printer.windows().name().isBlank()
                    ? windowsDefault.get() : printer.windows().name();
            case FILE -> fileDirectory.toAbsolutePath().toString();
        };
    }

    @Override
    public synchronized void destroy() {
        closeOpen();
    }

    /** A printer that cannot even be closed must not stop the till shutting down or reconnecting. */
    private void closeOpen() {
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (RuntimeException e) {
            log.warn("Could not close the connection to {}: {}", open.describe(), e.getMessage());
        }
        open = null;
    }
}
