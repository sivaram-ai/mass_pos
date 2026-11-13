package com.masspos.terminal;

import com.masspos.config.PosProperties;
import com.masspos.web.FieldRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The shop's counters, kept by an admin on the server.
 *
 * <p>A counter used to make itself up: somebody typed a number and a name into the window on that
 * machine, and the first sign-in created whatever they had typed. A typo made a till, two machines
 * could argue over one number, and the only place the shop could see what it had was a list of
 * whatever had turned up. The shop's counters are now a list the server owns, and a counter
 * machine does nothing but choose which of them it is.
 *
 * <p>The code is settled when the counter is created and never again. It is stamped into every
 * bill that till issues, and those bills are what the shop files: changing it would leave the
 * books describing a counter that no longer exists. The name is only ever shown on a screen, so
 * that is the part an admin can correct.
 */
@Service
public class TerminalAdmin {

    /** The same shape the code has always had: it lands in bill numbers, which are ≤16 characters. */
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]{0,2}");

    private final TerminalRepository terminals;
    private final PosProperties pos;

    public TerminalAdmin(TerminalRepository terminals, PosProperties pos) {
        this.terminals = terminals;
        this.pos = pos;
    }

    /** @param section floor, hall or area; optional */
    public record NewCounter(String code, String name, String section) {
    }

    @Transactional
    public Terminal create(NewCounter asked) {
        String code = asked.code() == null ? "" : asked.code().trim().toUpperCase(Locale.ROOT);
        String name = readable(asked.name());
        if (code.isEmpty()) {
            throw new FieldRejectedException("code", "Give the counter a number - it goes on every bill it takes.");
        }
        if (!CODE.matcher(code).matches()) {
            throw new FieldRejectedException("code", "Up to three characters, a letter first, e.g. T2."
                    + " (A name like MAIN goes in the Name box.)");
        }
        if (code.equals(pos.terminal().code())) {
            throw new FieldRejectedException("code", code + " is this server's own till. Give the counter a"
                    + " number of its own.");
        }
        if (name.isEmpty()) {
            throw new FieldRejectedException("name", "Give the counter a name - it is what staff see on"
                    + " every screen, e.g. Counter 2.");
        }
        terminals.findByCode(code).ifPresent(clash -> {
            throw new FieldRejectedException("code", code + " is already " + clash.getName()
                    + ". Counter numbers cannot be shared: two tills on one number would reuse bill numbers.");
        });
        return terminals.save(new Terminal(code, name, readable(asked.section()), TerminalType.COUNTER));
    }

    /** The name and where it stands. Never the code: see this class's note. */
    @Transactional
    public Terminal rename(UUID id, String name, String section) {
        Terminal counter = find(id);
        String called = readable(name);
        if (called.isEmpty()) {
            throw new FieldRejectedException("name", "Give the counter a name - it is what staff see on"
                    + " every screen, e.g. Counter 2.");
        }
        counter.rename(called, readable(section));
        return counter;
    }

    /**
     * Blocked, or let back in. A blocked counter keeps every bill it ever took - they are the
     * shop's records - and simply cannot be signed in on any more.
     */
    @Transactional
    public Terminal block(UUID id, boolean blocked) {
        Terminal counter = find(id);
        if (counter.getCode().equals(pos.terminal().code())) {
            throw new IllegalArgumentException("This is the server's own till. Blocking it would stop the"
                    + " shop billing at all.");
        }
        if (blocked) {
            counter.block();
        } else {
            counter.unblock();
        }
        return counter;
    }

    private Terminal find(UUID id) {
        return terminals.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No till " + id));
    }

    /**
     * Fit for a 60-character column and for a screen. Control characters are blanked rather than
     * refused: a name is cosmetic, and nothing should stop a shop naming its own counter.
     */
    private static String readable(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() <= 60 ? clean : clean.substring(0, 60).trim();
    }
}
