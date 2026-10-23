package com.masspos.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Which addresses the till answers on, decided by one setting rather than two.
 *
 * <p>A till on its own binds loopback, so nothing on the network can reach its screen or its cash
 * drawer - that has been true since the first slice and stays the default. A shop that sets
 * {@code pos.clients.max} above 0 has said it wants other machines to bill against this one, and
 * the port has to be open to the LAN for that to be possible. Making the shop set a bind address
 * as well would be two ways of saying one thing, and a shop that set one and forgot the other
 * would get a server nobody can reach, or a till on the network that nobody asked for.
 */
@Component
public class ServerBinding implements WebServerFactoryCustomizer<ConfigurableWebServerFactory> {

    private static final Logger log = LoggerFactory.getLogger(ServerBinding.class);

    private final ClientProperties clients;

    public ServerBinding(ClientProperties clients) {
        this.clients = clients;
    }

    @Override
    public void customize(ConfigurableWebServerFactory factory) {
        if (!clients.serving()) {
            return;
        }
        try {
            factory.setAddress(InetAddress.getByName("0.0.0.0"));
            log.info("Serving up to {} client machine(s): the billing screen is open to the LAN on this port,"
                    + " and {} is still the only thing that decides it", clients.max(), "pos.clients.max");
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("0.0.0.0 is not a resolvable address on this machine", impossible);
        }
    }
}
