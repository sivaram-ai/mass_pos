package com.masspos.hardware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The till's receipt printer. Jobs, drawer kicks and status queries all run on one worker thread,
 * so bytes from different jobs never interleave on the port, and callers submit work after their
 * database transaction has ended instead of holding SQLite's write lock through printer I/O.
 *
 * <p>Which printer that is comes from the settings screen on every job, so changing it takes effect
 * on the next bill without restarting the till.
 */
@Service
public class ThermalPrinter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ThermalPrinter.class);
    private static final Duration STATUS_TIMEOUT = Duration.ofMillis(500);
    /** Room for status round trips and a job queued ahead of this one. */
    private static final Duration AWAIT_MARGIN = Duration.ofSeconds(3);

    private final PrinterConnections connections;
    private final PrinterSettingsService printerSettings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "escpos-printer");
        thread.setDaemon(true);
        return thread;
    });

    public ThermalPrinter(PrinterConnections connections, PrinterSettingsService printerSettings) {
        this.connections = connections;
        this.printerSettings = printerSettings;
    }

    public EscPos newDocument() {
        PrinterProperties printer = settings();
        return new EscPos(printer.codePage(), printer.columns());
    }

    public CompletableFuture<Void> print(EscPos document) {
        return CompletableFuture.runAsync(() -> connections.current().write(document), worker)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        log.warn("Print job failed on {}: {}", target(), failure.getMessage());
                    }
                });
    }

    public CompletableFuture<Void> openDrawer() {
        PrinterProperties printer = settings();
        return print(newDocument().openDrawer(printer.drawerPin(), printer.drawerPulse()));
    }

    public CompletableFuture<PrinterStatus> status() {
        return CompletableFuture.supplyAsync(() -> PrinterStatus.query(connections.current(), STATUS_TIMEOUT), worker);
    }

    /** Waits for a job on behalf of an API caller. The job itself keeps running if the caller gives up. */
    public <T> T await(CompletableFuture<T> job) {
        Duration limit = settings().timeout().plus(AWAIT_MARGIN);
        try {
            return job.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PrinterException printerException) {
                throw printerException;
            }
            throw new PrinterException("Printing failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new PrinterException("Printer " + target() + " did not finish within " + limit.toSeconds() + " s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrinterException("Interrupted while waiting for the printer", e);
        }
    }

    /** The printer as it is set right now, not as it was when the till started. */
    public PrinterProperties settings() {
        return printerSettings.active();
    }

    public String target() {
        return settings().type() + " " + connections.describe();
    }

    @Override
    public void destroy() throws InterruptedException {
        worker.shutdown();
        worker.awaitTermination(5, TimeUnit.SECONDS);
    }
}
