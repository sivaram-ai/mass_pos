package com.masspos.counter;

import com.masspos.client.Discovery;
import com.masspos.desktop.PosPaths;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finding the shop's server, the three ways a shop might want to.
 *
 * <p>A cable and Wi-Fi are the same thing to this code, which is why there is no setting for
 * them: both are just a network card with an address, and a shop can have one counter on each
 * without anybody choosing. What differs is only how the address is <em>learned</em> - shouted
 * for, typed as a name or address, or given in full.
 *
 * <p>Discovery broadcasts on every network card that is up, so a counter with both a cable and
 * Wi-Fi asks down both and takes whichever answers. The answer is checked by actually opening the
 * billing screen's port afterwards: a shop with two servers on one LAN, or a stale answer from a
 * machine that has just gone down, should fail here rather than at the first bill.
 */
public final class ServerFinder {

    /** A shout should be answered in well under a second on a shop LAN; this is generous. */
    private static final Duration LISTEN = Duration.ofMillis(1200);
    private static final Duration REACHABLE = Duration.ofSeconds(3);

    private ServerFinder() {
    }

    /** A server that answered, and what it said about itself. */
    public record Found(String url, String shopName, int free, int places) {

        public boolean full() {
            return free <= 0;
        }

        public String describe() {
            return shopName + " at " + url + (places == 0 ? "" : " (" + free + " of " + places + " places free)");
        }
    }

    /**
     * Where this counter should connect, by whichever route it is set to use. Empty when nothing
     * answered, which the caller turns into a sentence rather than a stack trace.
     */
    public static Optional<Found> find(CounterConfig config) {
        return switch (config.find()) {
            case AUTOMATIC -> discover().stream().findFirst();
            case ADDRESS -> reachable(urlFor(config.address())).map(url -> new Found(url, "", 0, 0));
            case URL -> reachable(normalise(config.url())).map(url -> new Found(url, "", 0, 0));
        };
    }

    /**
     * Every shop server that answers, best first. More than one means a shop with two servers up,
     * which the settings screen shows rather than guessing between them.
     */
    public static List<Found> discover() {
        List<Found> found = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout((int) LISTEN.toMillis());
            byte[] ask = Discovery.ASK.getBytes(StandardCharsets.UTF_8);
            for (InetAddress to : broadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(ask, ask.length, to, PosPaths.DISCOVERY_PORT));
                } catch (IOException cardIsDown) {
                    // One card refusing is normal - a disconnected cable, a disabled adapter.
                    // The others still get their shout.
                }
            }
            listen(socket, found);
        } catch (IOException cannotAsk) {
            return List.of();
        }
        return found;
    }

    /** Collects answers until the socket times out, so two servers both get heard. */
    private static void listen(DatagramSocket socket, List<Found> found) {
        byte[] buffer = new byte[512];
        Set<String> seen = new LinkedHashSet<>();
        while (true) {
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(reply);
            } catch (IOException noMoreAnswers) {
                return;
            }
            String text = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
            String[] parts = text.split(Discovery.SEPARATOR);
            if (parts.length < 5 || !Discovery.ANSWER.equals(parts[0])) {
                continue;
            }
            String url = "http://" + reply.getAddress().getHostAddress() + ":" + parts[1];
            if (seen.add(url)) {
                found.add(new Found(url, parts[2], number(parts[3]), number(parts[4])));
            }
        }
    }

    /**
     * The broadcast address of every network card that is up, so a counter on a cable and a
     * counter on Wi-Fi both reach the server without being told which they are on.
     */
    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            for (NetworkInterface card : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!card.isUp() || card.isLoopback()) {
                    continue;
                }
                card.getInterfaceAddresses().stream()
                        .map(java.net.InterfaceAddress::getBroadcast)
                        .filter(java.util.Objects::nonNull)
                        .forEach(addresses::add);
            }
        } catch (IOException noCards) {
            // Fall through to the all-subnets address below, which is better than nothing.
        }
        if (addresses.isEmpty()) {
            try {
                addresses.add(InetAddress.getByName("255.255.255.255"));
            } catch (IOException impossible) {
                return List.of();
            }
        }
        // The server may be this very machine, which no broadcast comes back from.
        try {
            addresses.add(InetAddress.getLoopbackAddress());
        } catch (RuntimeException ignored) {
            // Nothing to do: the broadcast addresses above are still worth asking.
        }
        return addresses;
    }

    /** True when something is listening and it looks like a till. Checked before anyone signs in. */
    public static Optional<String> reachable(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI target = new URI(url);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(target.getHost(),
                        target.getPort() < 0 ? PosPaths.DEFAULT_PORT : target.getPort()),
                        (int) REACHABLE.toMillis());
            }
            return Optional.of(url);
        } catch (URISyntaxException | IOException nothingThere) {
            return Optional.empty();
        }
    }

    /**
     * Asks the server whether it is one, and how full. Done over HTTP after the port answers,
     * because a port that opens could be anything.
     */
    public static Optional<String> greet(String url) {
        try {
            HttpURLConnection http = (HttpURLConnection) new URI(url + "/api/setup").toURL().openConnection();
            http.setRequestProperty("X-POS-Client", "go-rockit-counter");
            http.setConnectTimeout((int) REACHABLE.toMillis());
            http.setReadTimeout((int) REACHABLE.toMillis());
            int status = http.getResponseCode();
            http.disconnect();
            // Anything the till answers at all - even a refusal - proves it is a till.
            return status > 0 ? Optional.of(url) : Optional.empty();
        } catch (URISyntaxException | IOException notATill) {
            return Optional.empty();
        }
    }

    /** "192.168.1.10" or "backoffice" becomes the whole address, with the port a till uses. */
    static String urlFor(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String trimmed = address.trim();
        return trimmed.contains("://") ? normalise(trimmed)
                : "http://" + trimmed + (trimmed.contains(":") ? "" : ":" + PosPaths.DEFAULT_PORT);
    }

    /** No trailing slash, so one join never produces two. */
    static String normalise(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
