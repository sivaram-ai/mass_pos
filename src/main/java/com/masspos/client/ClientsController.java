package com.masspos.client;

import com.masspos.auth.AuthContext;
import com.masspos.auth.RequiresRole;
import com.masspos.terminal.Terminal;
import com.masspos.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public ClientsController(ClientRegistry registry, CurrentCounter counter, TillPresence presence) {
        this.registry = registry;
        this.counter = counter;
        this.presence = presence;
    }

    /**
     * @param places     what pos.clients.max allows; 0 means this till serves nobody
     * @param thisCounter the counter the browser asking is signed in as
     */
    public record ClientsView(int places, int used, String thisCounter, List<ConnectedCounter> counters) {
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
        return new ClientsView(registry.places(), connected.size(), counter.code(),
                connected.stream().map(ConnectedCounter::of).toList());
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
