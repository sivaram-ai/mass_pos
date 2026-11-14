package com.masspos.counter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counter's one screen, and what happens when it is not there.
 *
 * <p>0.4.16 shipped a counter jar without {@code setup.html}. {@link CounterSetup#page()} threw an
 * {@code IOException} reading a resource that was not in the build, the agent's handler caught only
 * {@code RuntimeException}, so it escaped - and {@link HttpServer} answers an escaped exception by
 * closing the connection with nothing in it. A shop installing that counter saw a blank browser
 * error on the counter machine and on the server machine, with no clue in it and nothing in any
 * log, for the one screen that program has.
 */
class CounterAgentAnswersTest {

    /**
     * That the page is in the build is covered by {@code PrintAgentTest
     * .servesTheSetupPageWithoutAShopServerAnywhere}, which fetches it from a live agent, and by
     * the installer script, which counts it inside the jar it is about to ship. This is the other
     * half: if a build loses it again, or anything else in a handler throws, the person in front
     * of the counter gets a sentence rather than a blank page.
     */
    @Test
    void aHandlerThatThrowsStillAnswersTheBrowser() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        HttpHandler throwing = exchange -> {
            throw new IOException("setup.html is missing from this build");
        };
        server.createContext("/setup", PrintAgent.answering(throwing));
        server.start();
        try {
            HttpURLConnection asked = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/setup").toURL().openConnection();
            asked.setRequestProperty("Accept", "text/html,*/*");

            assertThat(asked.getResponseCode()).isEqualTo(500);
            String said = new String(asked.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(said)
                    .contains("GO RockIt Counter")
                    .contains("call service")
                    // Not the inside of the program, here least of all: this page is the only
                    // thing on screen and there is no log behind it.
                    .doesNotContain("setup.html", "IOException", "com.masspos");
        } finally {
            server.stop(0);
        }
    }
}
