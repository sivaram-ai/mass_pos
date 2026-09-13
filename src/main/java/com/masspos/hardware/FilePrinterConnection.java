package com.masspos.hardware;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for development and for tills without a printer: every job becomes
 * {@code job-<time>-<n>.bin} (the exact bytes a printer would get) plus a {@code .txt} preview.
 */
public class FilePrinterConnection implements PrinterConnection {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());

    private final Path directory;
    private final AtomicLong sequence = new AtomicLong();

    public FilePrinterConnection(Path directory) {
        this.directory = directory;
    }

    @Override
    public void write(EscPos document) {
        String name = "job-%s-%03d".formatted(STAMP.format(Instant.now()), sequence.incrementAndGet() % 1000);
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(name + ".bin"), document.toBytes());
            Files.writeString(directory.resolve(name + ".txt"), document.plainText());
        } catch (IOException e) {
            throw new PrinterException("Cannot write print job to " + directory, e);
        }
    }

    @Override
    public OptionalInt query(byte[] request, Duration timeout) {
        return OptionalInt.empty();
    }

    @Override
    public String describe() {
        return directory.toAbsolutePath().toString();
    }

    @Override
    public void close() {
    }
}
