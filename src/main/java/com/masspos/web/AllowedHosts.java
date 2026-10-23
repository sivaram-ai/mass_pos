package com.masspos.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The names a browser may use in its Host header to reach this till.
 *
 * <p>The Host check is what stops DNS rebinding: a page on evil.example cannot be let near this
 * server just because its name has been re-pointed at the till's address. On a single till the
 * rule is simply "loopback", which no outside name can satisfy.
 *
 * <p>A shop server has to be reachable by whatever its counters type or discover, so the rule
 * widens to this machine's own identities: its loopback names, every IP address its network cards
 * hold, and its hostname. An attacker's domain is none of those, so rebinding is still refused -
 * the browser would send {@code Host: evil.example} however the name resolves. Addresses are read
 * once at startup; a till handed a new IP mid-shift is restarted anyway, and re-reading the
 * interfaces on every request would put a syscall in front of every bill.
 */
final class AllowedHosts {

    private static final Logger log = LoggerFactory.getLogger(AllowedHosts.class);
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final Set<String> allowed;

    private AllowedHosts(Set<String> allowed) {
        this.allowed = allowed;
    }

    /** Loopback only: nothing on the network can reach this till. */
    static AllowedHosts loopbackOnly() {
        return new AllowedHosts(LOOPBACK);
    }

    /** Loopback, plus every address and name this machine answers to on the LAN. */
    static AllowedHosts thisMachineOnTheLan() {
        Set<String> names = new HashSet<>(LOOPBACK);
        try {
            for (NetworkInterface card : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (card.isUp()) {
                    Collections.list(card.getInetAddresses()).forEach(address -> add(names, address));
                }
            }
        } catch (SocketException e) {
            log.warn("Cannot read this machine's network addresses, so clients may be refused: {}", e.toString());
        }
        try {
            add(names, InetAddress.getLocalHost());
        } catch (UnknownHostException noName) {
            log.debug("This machine has no resolvable host name: {}", noName.toString());
        }
        log.info("Clients may reach this server as: {}", String.join(", ", names.stream().sorted().toList()));
        return new AllowedHosts(Set.copyOf(names));
    }

    boolean accepts(String host) {
        return allowed.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * Both the address and the name it resolves to, because a counter may have either typed in.
     * An IPv6 address is also stored in the brackets a Host header puts around it.
     */
    private static void add(Set<String> names, InetAddress address) {
        String literal = address.getHostAddress().toLowerCase(Locale.ROOT);
        // A link-local IPv6 address carries the interface it belongs to; no Host header has that.
        int zone = literal.indexOf('%');
        String plain = zone < 0 ? literal : literal.substring(0, zone);
        names.add(plain);
        if (!(address instanceof Inet4Address)) {
            names.add("[" + plain + "]");
        }
        String name = address.getHostName().toLowerCase(Locale.ROOT);
        if (!name.equals(plain)) {
            names.add(name);
        }
    }
}
