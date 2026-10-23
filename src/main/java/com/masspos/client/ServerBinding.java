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
            // Said out loud, because the silence was the bug: a shop that set a counter up and
            // watched it fail to find anything had no way to tell that the server was never
            // listening. This is the first line to look for when that happens.
            log.info("This till keeps to itself: pos.clients.max is 0, so it listens on loopback only and"
                    + " no other machine can find it or bill against it. Set pos.clients.max in"
                    + " <data-dir>/pos.properties to the number of counters this shop has, and restart.");
            return;
        }
        try {
            factory.setAddress(InetAddress.getByName("0.0.0.0"));
            log.info("Serving up to {} client machine(s): the billing screen is open to the network on this"
                    + " port. If a counter still cannot find this server, the next two things to check are"
                    + " Windows Firewall and whether the network is set to Private rather than Public.",
                    clients.max());
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("0.0.0.0 is not a resolvable address on this machine", impossible);
        }
    }
}
