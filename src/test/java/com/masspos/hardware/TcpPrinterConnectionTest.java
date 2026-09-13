package com.masspos.hardware;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcpPrinterConnectionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void sendsTheWholeJob() throws Exception {
        try (ServerSocket printer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (Socket client = printer.accept()) {
                    return client.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            EscPos job = new EscPos(CodePage.PC437, 32).line("hello").cut();

            connection(printer.getLocalPort()).write(job);

            assertThat(received.get(5, TimeUnit.SECONDS)).containsExactly(job.toBytes());
        }
    }

    @Test
    void readsTheRealTimeStatusReply() throws Exception {
        try (ServerSocket printer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<byte[]> request = CompletableFuture.supplyAsync(() -> {
                try (Socket client = printer.accept()) {
                    byte[] asked = client.getInputStream().readNBytes(3);
                    client.getOutputStream().write(0x12);
                    client.getOutputStream().flush();
                    return asked;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            assertThat(connection(printer.getLocalPort()).query(EscPos.statusRequest(1), TIMEOUT)).hasValue(0x12);
            assertThat(request.get(5, TimeUnit.SECONDS)).containsExactly(EscPos.statusRequest(1));
        }
    }

    @Test
    void unreachablePrinterFailsWithItsAddress() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        TcpPrinterConnection connection = connection(closedPort);

        assertThatThrownBy(() -> connection.write(new EscPos(CodePage.PC437, 32)))
                .isInstanceOf(PrinterException.class)
                .hasMessageContaining("127.0.0.1:" + closedPort);
        assertThatThrownBy(() -> connection.query(EscPos.statusRequest(1), TIMEOUT))
                .isInstanceOf(PrinterException.class)
                .hasMessageContaining("127.0.0.1:" + closedPort);
    }

    @Test
    void connectedButSilentPrinterIsNotAnError() throws Exception {
        try (ServerSocket printer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> silent = CompletableFuture.runAsync(() -> {
                try (Socket client = printer.accept()) {
                    client.getInputStream().readNBytes(3); // reads the request, never answers
                    Thread.sleep(1_000);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(connection(printer.getLocalPort()).query(EscPos.statusRequest(1), Duration.ofMillis(200))).isEmpty();
            silent.get(5, TimeUnit.SECONDS);
        }
    }

    private static TcpPrinterConnection connection(int port) {
        return new TcpPrinterConnection(new PrinterProperties.Tcp("127.0.0.1", port), TIMEOUT);
    }
}
