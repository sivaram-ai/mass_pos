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
import java.util.UUID;
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
    public String admit(String counterId, boolean overTheNetwork) {
        String own = pos.terminal().code();
        if (!overTheNetwork) {
            // The browser on the server bills as the server, whatever it was told to be: its bills
            // come off the server's own series and its printer is the server's printer.
            return own;
        }
        Terminal counter = counterFor(counterId);
        String refusal = whyNot(counter);
        if (refusal != null) {
            throw new ClientRefusedException(refusal);
        }
        // Heard from, and nothing more. The name and the code belong to the shop's own list of
        // counters, which an admin keeps on the server; a machine signing in has no say in either.
        counter.seen(Instant.now());
        return counter.getCode();
    }

    /**
     * The till a machine says it is, or null for anything this shop does not have: no id at all,
     * something that is not an id, or an id that is not one of these counters. All three are the
     * same answer to the machine asking - a refusal with a sentence - rather than a crash.
     */
    private Terminal counterFor(String counterId) {
        if (counterId == null || counterId.isBlank()) {
            return null;
        }
        try {
            return terminals.findById(UUID.fromString(counterId.trim())).orElse(null);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /** The same question asked by id, which is how a counter machine names the till it is. */
    @Transactional(readOnly = true)
    public String whyNot(String counterId) {
        return whyNot(counterFor(counterId));
    }

    /**
     * Why this counter could not bill against this server right now, in a sentence somebody
     * standing at it can act on - or null if it could.
     *
     * <p>The settings window on a counter asks this before it will let anybody press "Save and
     * start billing", and {@link #admit} asks it again at the sign-in itself. Same question, same
     * code, same words, so the window can never promise a place that the sign-in then refuses.
     * The second ask is the one that counts: between the two, another machine may have taken the
     * last place, and only the sign-in happens inside the transaction that settles who got it.
     */
    @Transactional(readOnly = true)
    public String whyNot(Terminal counter) {
        if (counter == null) {
            return "This machine has not been given a counter yet. Open GO RockIt Counter settings,"
                    + " look for the server, and choose which counter this machine is.";
        }
        String code = counter.getCode();
        if (!clients.serving()) {
            return "This till does not take other machines. Set pos.clients.max"
                    + " on the server to the number of counters it should serve.";
        }
        if (code.equals(pos.terminal().code())) {
            return "Counter " + code + " is the server itself. Choose one of the shop's other counters.";
        }
        if (!counter.isActive()) {
            return "Counter " + code + " (" + counter.getName() + ") has been blocked on the server."
                    + " An admin can unblock it under Settings, Tills in this shop.";
        }
        if (overLicenceLimit(counter)) {
            return "Counter " + code + " (" + counter.getName() + ") is beyond what this shop's licence"
                    + " covers, so it cannot bill. " + atTheLimit();
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
    public boolean holdsPlace(String counterId) {
        Terminal counter = counterFor(counterId);
        return counter != null && holds(counter);
    }

    /**
     * The counters a machine may choose to be: the shop's own list, minus the server itself.
     * Blocked ones come too, marked, because a counter that cannot find itself on the list has no
     * way of telling whether it was blocked or never created.
     */
    @Transactional(readOnly = true)
    public List<Terminal> choices() {
        return counters();
    }

    /**
     * Every counter this shop has, oldest first, leaving out the server's own till.
     *
     * <p>Oldest first because that order decides which of them the licence covers when a shop has
     * more counters than it is licensed for: the ones it had first keep working, and the extras
     * are the ones put out of use. Any other order would move the line between them about as
     * counters were renamed.
     */
    @Transactional(readOnly = true)
    public List<Terminal> counters() {
        String own = pos.terminal().code();
        return terminals.findAll().stream()
                .filter(terminal -> !terminal.getCode().equals(own))
                .filter(terminal -> terminal.getType() == TerminalType.COUNTER)
                .sorted(java.util.Comparator.comparing(Terminal::getCreatedAt)
                        .thenComparing(Terminal::getCode))
                .toList();
    }

    /** How many more counters this shop's licence has room for; 0 when it is at its limit. */
    @Transactional(readOnly = true)
    public int roomForMoreCounters() {
        return Math.max(0, clients.max() - counters().size());
    }

    /**
     * True for a counter the licence does not cover: it exists, but the shop has more counters than
     * {@code pos.clients.max} allows and this is one of the extras.
     *
     * <p>It can happen without anybody doing anything wrong - a shop whose licence is cut from
     * three counters to one still has the other two rows, and their bills. So they are kept and
     * marked rather than deleted, and simply cannot be billed on.
     */
    @Transactional(readOnly = true)
    public boolean overLicenceLimit(Terminal counter) {
        List<Terminal> counters = counters();
        int at = -1;
        for (int i = 0; i < counters.size(); i++) {
            if (counters.get(i).getCode().equals(counter.getCode())) {
                at = i;
                break;
            }
        }
        return at >= clients.max();
    }

    /** What to tell somebody who has run out of counters, naming the number they are allowed. */
    public String atTheLimit() {
        int max = clients.max();
        return "Maximum counter limit reached. Your current licence supports only " + max
                + (max == 1 ? " additional counter" : " additional counters")
                + ". Please contact support to add more counters.";
    }

    /** True when that till is one of the ones currently holding a place. */
    @Transactional(readOnly = true)
    public boolean holds(Terminal counter) {
        return taken().stream().anyMatch(held -> held.getCode().equals(counter.getCode()));
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
