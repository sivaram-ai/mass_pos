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
import java.util.Set;
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
 * <p>The count is of counters somebody is signed in on, not of counters that have ever said hello.
 * A shop that replaces a machine, or reimages one, would otherwise spend a slot forever on a till
 * that no longer exists, and would have to be told to go and delete a row before the new one could
 * be used. Logging out hands the place back at once; a machine that is switched off without anyone
 * logging out holds it until the idle window has passed with no word from it.
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
    private final TillPresence presence;

    public ClientRegistry(TerminalRepository terminals, PosProperties pos, ClientProperties clients,
                          TillPresence presence) {
        this.terminals = terminals;
        this.pos = pos;
        this.clients = clients;
        this.presence = presence;
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
        String code = normalise(counterCode);
        if (!overTheNetwork && code.isEmpty()) {
            return own;
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
        // Every sign-in, not only the first one this machine ever made. A counter used to be let in
        // for good once it had a row in the table, so a shop set up for two could have three
        // counters billing at once as long as each had signed in some time in the past.
        String refusal = whyNot(code);
        if (refusal != null) {
            throw new ClientRefusedException(refusal);
        }
        String called = readableName(name);
        Terminal known = terminals.findByCode(code).orElse(null);
        if (known == null) {
            known = terminals.save(new Terminal(code, called.isEmpty() ? code : called, "", TerminalType.COUNTER));
            log.info("Counter {} joined the shop; {} of {} places now taken", code, taken().size() + 1,
                    clients.max());
        }
        // The name travels on every sign-in, not only the first: a shop that renames a machine in
        // its own settings window should see the new name on its screens, which is the only place
        // the name is ever shown.
        known.describe(called.isEmpty() ? known.getName() : called, known.getSection(), TerminalType.COUNTER,
                Instant.now());
        return code;
    }

    /**
     * Why a counter calling itself {@code counterCode} could not bill against this server right
     * now, in a sentence somebody standing at the counter can act on - or null if it could.
     *
     * <p>The settings window on a counter asks this before it will let anybody press "Save and
     * start billing", and {@link #admit} asks it again at the sign-in itself. Same question, same
     * code, same words, so the window can never promise a place that the sign-in then refuses.
     * The second ask is the one that counts: between the two, another machine may have taken the
     * last place, and only the sign-in happens inside the transaction that settles who got it.
     */
    @Transactional(readOnly = true)
    public String whyNot(String counterCode) {
        String code = normalise(counterCode);
        if (code.isEmpty()) {
            return "This machine has not been given a counter number yet."
                    + " Open GO RockIt Counter settings and set one.";
        }
        if (!CODE.matcher(code).matches()) {
            return "\"" + code + "\" is not a usable counter number:"
                    + " up to three characters, a letter first, e.g. T2.";
        }
        if (!clients.serving()) {
            return "This till does not take other machines. Set pos.clients.max"
                    + " on the server to the number of counters it should serve.";
        }
        if (code.equals(pos.terminal().code())) {
            return "Counter " + code + " is the server itself. Give this machine a"
                    + " counter number of its own.";
        }
        List<Terminal> others = taken(code);
        if (others.size() >= clients.max()) {
            return "This shop is set up for " + clients.max() + " counter"
                    + (clients.max() == 1 ? "" : "s") + " and " + (clients.max() == 1 ? "it is" : "they are")
                    + " in use (" + others.stream().map(Terminal::getCode).sorted().reduce((a, b) -> a + ", " + b)
                            .orElse("")
                    + "). Raise pos.clients.max on the server, or wait for one to sign out, before "
                    + code + " can bill.";
        }
        return null;
    }

    /** Places not taken by another counter, for the counter settings window and the tills list. */
    @Transactional(readOnly = true)
    public int free() {
        return Math.max(0, clients.max() - taken().size());
    }

    /** True while this counter holds one of the shop's places, i.e. somebody is signed in on it. */
    @Transactional(readOnly = true)
    public boolean holdsPlace(String counterCode) {
        String code = normalise(counterCode);
        return !code.isEmpty() && taken().stream().anyMatch(terminal -> terminal.getCode().equals(code));
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

    private static String normalise(String counterCode) {
        return counterCode == null ? "" : counterCode.trim().toUpperCase(Locale.ROOT);
    }

    private List<Terminal> taken() {
        return taken("");
    }

    /**
     * The counters holding a place, leaving out {@code exceptCode} - the machine asking, which is
     * never counted against itself.
     *
     * <p>A place is held by a signed-in session, not by a machine that once said hello. Somebody
     * pressing Log out gives the place back at once, which is what a shop expects when it swaps
     * who is on a counter. A machine that is simply switched off, or carried away, cannot say
     * anything, so its place also falls away once the idle window has passed with no word from it
     * - long enough that a reboot or a lunch break never costs a counter its place.
     */
    private List<Terminal> taken(String exceptCode) {
        Instant since = Instant.now().minus(clients.idleMinutes(), ChronoUnit.MINUTES);
        String own = pos.terminal().code();
        Set<String> signedIn = presence.signedIn();
        return terminals.findAll().stream()
                .filter(terminal -> !terminal.getCode().equals(own))
                .filter(terminal -> !terminal.getCode().equals(exceptCode))
                .filter(terminal -> signedIn.contains(terminal.getCode()))
                .filter(terminal -> terminal.getLastSeenAt() != null && terminal.getLastSeenAt().isAfter(since))
                .sorted((a, b) -> b.getLastSeenAt().compareTo(a.getLastSeenAt()))
                .toList();
    }
}
