package com.masspos.client;

import com.masspos.auth.AuthContext;
import com.masspos.auth.RequiresRole;
import com.masspos.settings.ShopSettingsService;
import com.masspos.terminal.Terminal;
import com.masspos.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The counters billing against this server, for the Settings screen and for the counters themselves. */
@RestController
@RequestMapping("/api/clients")
public class ClientsController {

    private final ClientRegistry registry;
    private final CurrentCounter counter;
    private final TillPresence presence;
    private final ShopSettingsService shop;

    public ClientsController(ClientRegistry registry, CurrentCounter counter, TillPresence presence,
                             ShopSettingsService shop) {
        this.registry = registry;
        this.counter = counter;
        this.presence = presence;
        this.shop = shop;
    }

    /**
     * @param places     what pos.clients.max allows; 0 means this till serves nobody
     * @param free       places no counter is signed in on, so a machine could still join
     * @param thisCounter the counter the browser asking is signed in as
     */
    public record ClientsView(int places, int used, int free, String thisCounter,
                              List<ConnectedCounter> counters) {
    }

    /**
     * What a counter needs to know before anybody signs in on it: whether this is a shop server,
     * whether it has room, and if not, why not in a sentence.
     *
     * @param shop       the shop's name, so the settings window can say which server it found
     * @param holdsPlace true when this counter is already one of the machines billing here
     * @param canJoin    true when somebody could sign in on this counter right now
     * @param refusal    why not, or null when they could
     */
    public record Places(String shop, int places, int used, int free, boolean holdsPlace, boolean canJoin,
                         String refusal) {
    }

    /**
     * One of the counters a machine may choose to be.
     *
     * @param id      what the machine keeps and sends; the code and the name are only ever shown
     * @param taken   somebody is signed in on it, so this machine cannot be it right now
     * @param blocked an admin has stopped it being used; it is listed so a counter that cannot
     *                find itself knows which of the two happened
     */
    public record Choice(String id, String code, String name, String section, boolean taken,
                         boolean blocked) {
    }

    /** @param counters the shop's counters, for the picker on a counter's settings window */
    public record Counters(String shop, int places, int free, List<Choice> counters) {
    }

    /**
     * The counters this shop has, for a machine deciding which of them it is. No token, for the
     * same reason {@code /places} needs none: it is asked before anybody can sign in. It says what
     * discovery already broadcasts to the whole LAN, plus the counters' names - which is what the
     * person standing at the machine has to recognise to pick the right one.
     */
    @GetMapping("/counters")
    public Counters counters() {
        return new Counters(shop.currentOrEmpty().getTradeName(), registry.places(), registry.free(),
                registry.choices().stream()
                        .map(counter -> new Choice(counter.getId().toString(), counter.getCode(),
                                counter.getName(), counter.getSection(), registry.holds(counter),
                                !counter.isActive()))
                        .toList());
    }

    public record ConnectedCounter(String code, String name, String section, Instant lastSeenAt) {

        static ConnectedCounter of(Terminal terminal) {
            return new ConnectedCounter(terminal.getCode(), terminal.getName(), terminal.getSection(),
                    terminal.getLastSeenAt());
        }
    }

    /** Readable by anyone signed in: a cashier should be able to see which counter they are on. */
    @GetMapping
    public ClientsView get() {
        List<Terminal> connected = registry.connected();
        return new ClientsView(registry.places(), connected.size(), registry.free(), counter.code(),
                connected.stream().map(ConnectedCounter::of).toList());
    }

    /**
     * Whether a counter could bill here, asked by its settings window before anybody has signed
     * in - which is why this one answer needs no token.
     *
     * <p>It gives away nothing that is not already shouted to the whole shop network: discovery
     * answers every machine on the LAN with the shop's name, how many counters this server is set
     * up for and how many places are free. What decides who may actually bill is still the PIN.
     */
    @GetMapping("/places")
    public Places places(@RequestParam(required = false) String counter) {
        // `counter` is the till's id: a counter is told which till it is, it does not name one.
        String refusal = registry.whyNot(counter);
        List<Terminal> connected = registry.connected();
        return new Places(shop.currentOrEmpty().getTradeName(), registry.places(), connected.size(),
                registry.free(), registry.holdsPlace(counter), refusal == null, refusal);
    }

    /**
     * "I am still here." Every signed-in screen calls this on a timer, so the shop can see which
     * tills somebody is actually standing at and a counter's place is only given up once the
     * machine has really gone quiet. Cheap on purpose: usually nothing but a map entry.
     */
    @PostMapping("/here")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stillThere() {
        AuthContext.require();
        String here = counter.code();
        presence.heard(here);
        registry.stillThere(here);
    }

    /**
     * Signs a till out from here and gives its place back, for the machine that was switched off
     * or carried away without anybody logging out. Its session is ended, so the place is free at
     * once rather than when the idle window runs out - which on a shop set up for one counter is
     * the difference between the next machine billing now and not billing until this evening.
     */
    @PostMapping("/{code}/release")
    @RequiresRole({UserRole.ADMIN})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@org.springframework.web.bind.annotation.PathVariable String code) {
        registry.release(code);
        presence.signOut(code);
    }
}
