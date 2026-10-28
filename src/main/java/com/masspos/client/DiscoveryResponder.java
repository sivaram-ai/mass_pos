package com.masspos.client;

import com.masspos.settings.ShopSettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

/**
 * Answers a counter that is looking for its shop's server, so nobody has to find out the back
 * office PC's IP address and type it in.
 *
 * <p>A counter broadcasts one short datagram on the shop's network; every server that hears it
 * answers with the port to bill on, the shop's name and how many places are left. Broadcast rather
 * than mDNS: it is a dozen lines against a dependency, it works on a flat shop LAN with no router
 * configuration, and a shop with a network segmented enough to swallow a broadcast is a shop whose
 * installer can type an address.
 *
 * <p>Only a server listens. A till on its own answers nothing, so a counter cannot find and try to
 * bill against a machine that is not serving. The answer carries no secret: it says a shop server
 * is here, which anyone on the LAN could learn by connecting to the port anyway, and signing in
 * still decides everything that matters.
 */
@Component
public class DiscoveryResponder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryResponder.class);
    /** Long enough for the longest answer; anything bigger is not ours. */
    private static final int MAX_DATAGRAM = 512;

    private final ClientProperties clients;
    private final ClientRegistry registry;
    private final ShopSettingsService shop;
    private final ApplicationContext context;
    private volatile DatagramSocket socket;
    private volatile boolean listening;

    public DiscoveryResponder(ClientProperties clients, ClientRegistry registry, ShopSettingsService shop,
                              ApplicationContext context) {
        this.clients = clients;
        this.registry = registry;
        this.shop = shop;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!clients.serving()) {
            return;
        }
        try {
            socket = new DatagramSocket(clients.discoveryPort());
        } catch (IOException cannotListen) {
            // Never fatal: a shop can always type the address in. Another copy of the server, or
            // another program on the port, should not stop this one billing.
            log.warn("Counters will not find this server by themselves on UDP {}: {}. They can still be"
                    + " pointed at it by address.", clients.discoveryPort(), cannotListen.toString());
            return;
        }
        listening = true;
        Thread listener = new Thread(this::listen, "client-discovery");
        listener.setDaemon(true);
        listener.start();
        log.info("Counters can find this server by themselves on UDP {}", clients.discoveryPort());
    }

    @PreDestroy
    void stop() {
        listening = false;
        if (socket != null) {
            socket.close();
        }
    }

    private void listen() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (listening) {
            try {
                DatagramPacket asked = new DatagramPacket(buffer, buffer.length);
                socket.receive(asked);
                String text = new String(asked.getData(), asked.getOffset(), asked.getLength(),
                        StandardCharsets.UTF_8).trim();
                if (!Discovery.ASK.equals(text)) {
                    continue;
                }
                byte[] answer = answer().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(answer, answer.length, asked.getAddress(), asked.getPort()));
            } catch (IOException closed) {
                if (listening) {
                    log.debug("Discovery listener hiccup: {}", closed.toString());
                }
            } catch (RuntimeException e) {
                // A bad datagram must never take the listener down, or the shop silently loses
                // discovery until the next restart.
                log.warn("Could not answer a counter looking for this server: {}", e.toString());
            }
        }
    }

    private String answer() {
        int taken = registry.connected().size();
        return Discovery.answer(port(), shopName(), Math.max(0, clients.max() - taken), clients.max());
    }

    private String shopName() {
        try {
            String name = shop.currentOrEmpty().getTradeName();
            return name == null || name.isBlank() ? "GO RockIt" : name;
        } catch (RuntimeException notReadyYet) {
            return "GO RockIt";
        }
    }

    /** The port the billing screen is actually on, which a test or a second till may have moved. */
    private int port() {
        return context instanceof WebServerApplicationContext web && web.getWebServer() != null
                ? web.getWebServer().getPort()
                : 8765;
    }
}
