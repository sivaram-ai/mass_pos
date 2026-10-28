package com.masspos.client;

import com.masspos.auth.AuthContext;
import com.masspos.auth.Principal;
import com.masspos.config.PosProperties;
import org.springframework.stereotype.Component;

/**
 * Which counter the request being handled belongs to.
 *
 * <p>Before there were clients this was always one answer - the machine's own
 * {@code pos.terminal.code} - and fifteen places asked {@code PosProperties} for it directly. With
 * counters billing against a server those places mean something different: not "this machine" but
 * "the till this bill is being rung up on", which is now whichever machine the browser is running
 * on. Bill numbers, held bills, stock movements and audit revisions all hang off it, so there is
 * one answer in one place rather than fifteen chances to use the wrong one.
 *
 * <p>The counter is decided once, when somebody signs in, and kept on their token. Taking it from
 * a header on each request instead would let a signed-in browser renumber its bills by editing a
 * request; taking it from the client's IP address would break the first time a shop's router
 * handed out different ones.
 */
@Component
public class CurrentCounter {

    private final PosProperties pos;

    public CurrentCounter(PosProperties pos) {
        this.pos = pos;
    }

    /**
     * Never null. Falls back to this machine's own code off a request, or on one where nobody is
     * signed in - a background job, a test, or the first start registering the terminal.
     */
    public String code() {
        Principal principal = AuthContext.current();
        return principal == null || principal.counter() == null || principal.counter().isBlank()
                ? pos.terminal().code()
                : principal.counter();
    }

    /** This machine, whoever is signed in: its printer, its drawer, its own folder. */
    public String thisMachine() {
        return pos.terminal().code();
    }
}
