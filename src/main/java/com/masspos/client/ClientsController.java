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
     * Lets a counter go before its idle time is up, so a shop swapping a machine over does not
     * have to wait for a place to come free.
     */
    @PostMapping("/{code}/release")
    @RequiresRole({UserRole.ADMIN})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@org.springframework.web.bind.annotation.PathVariable String code) {
        registry.release(code);
        presence.gone(code);
    }
}
