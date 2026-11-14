package com.masspos.counter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Setting a counter up, as a page this machine serves itself.
 *
 * <p>It used to be a window belonging to the counter program. That window existed for one reason:
 * everything it decides has to be settled <em>before</em> there is a shop server to serve a page,
 * so there was nothing else to put it in. There is now - the print agent is already a web server
 * on this machine, running before anything has been found and whether or not the shop's server is
 * reachable, so the page can come from here and the whole product is one kind of thing again.
 *
 * <p>This class is the part worth testing: what the page asks for and what it is told. The page
 * itself is {@code setup.html} beside it, and the plumbing is in {@link PrintAgent}.
 */
final class CounterSetup {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What a shop is told when nothing answers. Two of the three things that are ever actually
     * wrong are on the <em>server's</em> machine, which is the part nobody guesses, so they are
     * named rather than sending somebody to look at the cable.
     */
    private static final List<String> WHAT_TO_CHECK = List.of(
            "On the server, is GO RockIt running and set to serve counters? Open its data folder,"
                    + " open pos.properties, and check pos.clients.max is the number of counters the"
                    + " shop has. 0 means the server keeps to itself. Restart it after changing this.",
            "Did Windows Firewall ask about GO RockIt on the server, and was it allowed? Refused"
                    + " once, Windows remembers, and no counter will ever reach it.",
            "Is the network set to Private rather than Public, on both computers? Windows stops"
                    + " machines reaching each other on a Public network whatever the firewall says.",
            "If all three are right, type the server's address above instead of looking for it:"
                    + " some networks do not pass the question on.");

    private CounterSetup() {
    }

    /** The page itself, read out of the jar beside this class. */
    static byte[] page() throws IOException {
        try (InputStream html = CounterSetup.class.getResourceAsStream("setup.html")) {
            if (html == null) {
                throw new IOException("setup.html is missing from this build");
            }
            return html.readAllBytes();
        }
    }

    /** What this machine already had, so the page opens on the settings it is changing. */
    static byte[] state(CounterConfig config) {
        ObjectNode state = JSON.createObjectNode();
        state.put("find", config.find().name());
        state.put("address", config.address());
        state.put("url", config.url());
        state.put("counterId", config.counterId());
        state.put("counterCode", config.code());
        return bytes(state);
    }

    /**
     * Looks for the server the page is asking about and, if it answers, the counters it offers.
     *
     * <p>Every counter comes back with whether this machine could be it and, when it could not,
     * the reason in the server's own words - so the page never has to work out for itself what a
     * blocked or over-licence counter means.
     */
    static byte[] look(CounterConfig asked) {
        ObjectNode answer = JSON.createObjectNode();
        Optional<ServerFinder.Found> server = ServerFinder.find(asked);
        if (server.isEmpty()) {
            answer.put("found", false);
            answer.put("trouble", asked.find() == CounterConfig.Find.AUTOMATIC
                    ? "No shop server answered on this network."
                    : "Nothing answered at that address.");
            ArrayNode steps = answer.putArray("steps");
            if (asked.find() == CounterConfig.Find.AUTOMATIC) {
                WHAT_TO_CHECK.forEach(steps::add);
            } else {
                steps.add("Check the address, and that GO RockIt is running on that computer.");
                steps.add("Check pos.clients.max is above 0 on the server, and that Windows Firewall"
                        + " has allowed GO RockIt there.");
            }
            return bytes(answer);
        }
        String url = server.get().url();
        answer.put("found", true);
        answer.put("server", url);
        answer.put("counterId", asked.counterId());
        List<ServerFinder.Choice> choices = ServerFinder.countersAt(url);
        ArrayNode counters = answer.putArray("counters");
        String shop = "";
        for (ServerFinder.Choice choice : choices) {
            ObjectNode one = counters.addObject();
            one.put("id", choice.id());
            one.put("code", choice.code());
            one.put("name", choice.name());
            one.put("label", choice.toString());
            // Asked of the server rather than guessed here, so the page can never offer a counter
            // the sign-in would then turn away.
            Optional<ServerFinder.Room> room = ServerFinder.roomFor(url, choice.id());
            boolean canUse = room.map(ServerFinder.Room::canJoin).orElse(false);
            one.put("canUse", canUse);
            one.put("why", canUse ? "" : room.map(ServerFinder.Room::refusal)
                    .orElse("The shop's server stopped answering while this page was asking."
                            + " Press \"Look for the server\" again."));
            if (shop.isEmpty()) {
                shop = room.map(ServerFinder.Room::shop).orElse("");
            }
        }
        answer.put("shop", shop);
        return bytes(answer);
    }

    /**
     * Takes the counter this machine is to be, after asking the server one last time.
     *
     * <p>The page has already been told it may; this asks again because between the two another
     * machine may have taken the last place, and the sign-in that follows would be the one to
     * discover it - on a screen with no way to put it right.
     *
     * @return the billing screen to go to, or empty with {@code answer} carrying the refusal
     */
    static Optional<String> save(CounterConfig config, JsonNode asked, ObjectNode answer) {
        CounterConfig.Find how = find(asked.path("find").asText(""));
        config.find(how);
        config.address(asked.path("address").asText(""));
        config.url(asked.path("url").asText(""));
        String counterId = asked.path("counterId").asText("");
        if (counterId.isBlank()) {
            answer.put("error", "Choose which of the shop's counters this machine is.");
            return Optional.empty();
        }
        Optional<ServerFinder.Found> server = ServerFinder.find(config);
        if (server.isEmpty()) {
            answer.put("error", "The shop's server stopped answering. Press \"Look for the server\""
                    + " and try again.");
            return Optional.empty();
        }
        String url = server.get().url();
        Optional<ServerFinder.Choice> choice = ServerFinder.countersAt(url).stream()
                .filter(one -> one.id().equals(counterId))
                .findFirst();
        if (choice.isEmpty()) {
            answer.put("error", "That counter is no longer on the shop's list. Press \"Look for the"
                    + " server\" to see the counters it has now.");
            return Optional.empty();
        }
        Optional<ServerFinder.Room> room = ServerFinder.roomFor(url, counterId);
        if (room.isEmpty() || !room.get().canJoin()) {
            answer.put("error", room.map(ServerFinder.Room::refusal)
                    .orElse("The shop's server would not take this counter. Press \"Look for the"
                            + " server\" and try again."));
            return Optional.empty();
        }
        config.counter(choice.get().id(), choice.get().code(), choice.get().name());
        config.lastServer(url);
        try {
            config.save();
        } catch (IOException cannotWrite) {
            answer.put("error", "This counter's settings could not be saved to "
                    + CounterConfig.file() + ". Check the folder is not read-only, and try again.");
            return Optional.empty();
        }
        return Optional.of(url);
    }

    static CounterConfig.Find find(String name) {
        try {
            return CounterConfig.Find.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notOne) {
            return CounterConfig.Find.AUTOMATIC;
        }
    }

    static JsonNode read(byte[] body) {
        try {
            return JSON.readTree(body == null || body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body);
        } catch (IOException notJson) {
            return JSON.createObjectNode();
        }
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    static byte[] bytes(JsonNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException impossible) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }
}
