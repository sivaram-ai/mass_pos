package com.masspos.client;

import com.masspos.config.PosProperties;
import com.masspos.terminal.Terminal;
import com.masspos.terminal.TerminalRepository;
import com.masspos.terminal.TerminalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which machines are allowed to bill against this one, and how many at a time.
 *
 * <p>A counter is admitted when somebody signs in on it. That is the right moment: it is the first
 * thing a client does, it already fails loudly on screen, and a machine that cannot sign in cannot
 * bill. Admission writes the counter into the same {@code terminal} table this machine registers
 * itself in, so the shop's list of tills and the list of clients are one list - reports and the
 * audit trail have always been tagged by terminal code, and nothing had to learn a new word.
 *
 * <p>The count is of counters seen recently, not of counters ever seen. A shop that replaces a
 * machine, or reimages one, would otherwise spend a slot forever on a till that no longer exists,
 * and would have to be told to go and delete a row before the new one could be used.
 *
 * <p>Nothing here is a substitute for signing in: it decides which <em>machines</em> may take part,
 * while the PIN still decides which <em>person</em> is billing and what they may do.
 */
@Service
public class ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);
    /** The same shape as pos.terminal.code, because it lands in the same column and in bill numbers. */
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]{0,2}");

    private final TerminalRepository terminals;
    private final PosProperties pos;
    private final ClientProperties clients;

    public ClientRegistry(TerminalRepository terminals, PosProperties pos, ClientProperties clients) {
        this.terminals = terminals;
        this.pos = pos;
        this.clients = clients;
    }

    /**
     * Lets a counter in, or says why not. Returns the counter code the session belongs to, which
     * is what its bills are numbered under.
     *
     * @param counterCode what the machine calls itself, null or blank from the server's own browser
     * @param overTheNetwork false for the browser on the server machine, which needs no permission
     */
    @Transactional
    public String admit(String counterCode, String name, boolean overTheNetwork) {
        String own = pos.terminal().code();
        String code = counterCode == null ? "" : counterCode.trim().toUpperCase(Locale.ROOT);
        if (!overTheNetwork && code.isEmpty()) {
            return own;
        }
        if (code.isEmpty()) {
            throw new ClientRefusedException("This machine has not been given a counter number yet."
                    + " Open GO RockIt Counter settings and set one.");
        }
        if (!CODE.matcher(code).matches()) {
            throw new ClientRefusedException("\"" + code + "\" is not a usable counter number:"
                    + " up to three characters, a letter first, e.g. T2.");
        }
        if (!overTheNetwork) {
            // The server's own browser may name a counter, but only its own: its bills come off
            // the server's own series and its printer is the server's printer.
            if (!code.equals(own)) {
                throw new ClientRefusedException("This browser is on the server itself, which bills as "
                        + own + ". Counter " + code + " has to sign in from its own machine.");
            }
            return own;
        }
        if (!clients.serving()) {
            throw new ClientRefusedException("This till does not take other machines. Set pos.clients.max"
                    + " on the server to the number of counters it should serve.");
        }
        if (code.equals(own)) {
            throw new ClientRefusedException("Counter " + code + " is the server itself. Give this machine a"
                    + " counter number of its own.");
        }
        String called = readableName(name);
        Terminal known = terminals.findByCode(code).orElse(null);
        if (known == null) {
            refuseIfFull(code);
            known = terminals.save(new Terminal(code, called.isEmpty() ? code : called, "", TerminalType.COUNTER));
            log.info("Counter {} joined the shop; {} of {} places now taken", code, taken().size(), clients.max());
        }
        // The name travels on every sign-in, not only the first: a shop that renames a machine in
        // its own settings window should see the new name on its screens, which is the only place
        // the name is ever shown.
        known.describe(called.isEmpty() ? known.getName() : called, known.getSection(), TerminalType.COUNTER,
                Instant.now());
        return code;
    }

    /**
     * Keeps a counter's place while it is working, so its slot is only freed once it goes quiet.
     *
     * <p>The row is only touched once it is halfway to going stale, not on every call. Screens say
     * "still here" every minute; {@code terminal} is audited, so writing each one would put a
     * revision a minute a till into the trail the shop's real changes have to be found in. Half the
     * idle window is early enough that a till at work never loses its place, and rare enough that
     * the trail still reads.
     */
    @Transactional
    public void stillThere(String code) {
        Instant now = Instant.now();
        Instant refreshAfter = now.minus(clients.idleMinutes() / 2, ChronoUnit.MINUTES);
        terminals.findByCode(code)
                .filter(terminal -> terminal.getLastSeenAt() == null || terminal.getLastSeenAt().isBefore(refreshAfter))
                .ifPresent(terminal ->
                        terminal.describe(terminal.getName(), terminal.getSection(), terminal.getType(), now));
    }

    /**
     * Gives a counter's place back now rather than when it goes quiet, for a shop swapping a
     * machine over. The row stays: its code is on bills and in the audit trail and has to keep
     * reading. Only the "last seen" is wound back, which is what the count looks at.
     */
    @Transactional
    public void release(String code) {
        terminals.findByCode(code).ifPresent(terminal -> terminal.describe(terminal.getName(),
                terminal.getSection(), terminal.getType(),
                Instant.now().minus(clients.idleMinutes() + 1L, ChronoUnit.MINUTES)));
    }

    /** The counters billing against this server now, newest contact first. The server itself is not one. */
    @Transactional(readOnly = true)
    public List<Terminal> connected() {
        return taken();
    }

    public int places() {
        return clients.max();
    }

    /**
     * What the machine calls itself, fit for a 60-character column and for a screen. Control
     * characters are blanked rather than refused: a name is cosmetic, and a counter must never be
     * stopped from billing over one.
     */
    private static String readableName(String name) {
        if (name == null) {
            return "";
        }
        String clean = name.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() <= 60 ? clean : clean.substring(0, 60).trim();
    }

    private void refuseIfFull(String joining) {
        List<Terminal> taken = taken();
        if (taken.size() >= clients.max()) {
            throw new ClientRefusedException("This shop is set up for " + clients.max() + " counter"
                    + (clients.max() == 1 ? "" : "s") + " and " + (clients.max() == 1 ? "it is" : "they are")
                    + " in use (" + taken.stream().map(Terminal::getCode).sorted().reduce((a, b) -> a + ", " + b)
                            .orElse("")
                    + "). Raise pos.clients.max on the server, or wait for one to finish, before "
                    + joining + " can bill.");
        }
    }

    /**
     * Counters that have been heard from inside the idle window. A machine switched off at the end
     * of the day gives its place back by morning without anybody having to do anything.
     */
    private List<Terminal> taken() {
        Instant since = Instant.now().minus(clients.idleMinutes(), ChronoUnit.MINUTES);
        String own = pos.terminal().code();
        return terminals.findAll().stream()
                .filter(terminal -> !terminal.getCode().equals(own))
                .filter(terminal -> terminal.getLastSeenAt() != null && terminal.getLastSeenAt().isAfter(since))
                .sorted((a, b) -> b.getLastSeenAt().compareTo(a.getLastSeenAt()))
                .toList();
    }
}
