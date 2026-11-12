package com.masspos.client;

import com.masspos.auth.AuthToken;
import com.masspos.auth.AuthTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Which tills somebody is standing at right now, and who.
 *
 * <p>Two things have to be true and neither is enough on its own. Somebody must be signed in,
 * because a machine sitting on the sign-in page is not billing. And the screen must have said
 * "still here" in the last few minutes, because a counter switched off at the end of the day
 * without anyone signing out would otherwise read as in use all night.
 *
 * <p>The "still here" times are kept in memory, not in the database. They are worth nothing an
 * hour later, every till would write a row a minute, and {@code terminal} is audited - a day of
 * heartbeats from four counters would bury the day's real changes under 5,000 revisions. A server
 * restart simply forgets, and every till says so again within the minute.
 */
@Service
public class TillPresence {

    /** The screen checks in every minute, so three missed words is a machine nobody is at. */
    private static final Duration SILENT_AFTER = Duration.ofMinutes(3);

    private final AuthTokenRepository tokens;
    private final Map<String, Instant> heardFrom = new ConcurrentHashMap<>();

    public TillPresence(AuthTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * @param inUse    somebody is signed in and the screen is answering: a till being billed on
     * @param signedIn somebody is signed in, answering or not - which is what holds the till's
     *                 place in the shop, so the two have to be told apart or the list of tills
     *                 and the count of free places appear to contradict each other
     * @param staff    who is signed in, or null when nobody is
     */
    public record Presence(boolean inUse, boolean signedIn, String staff) {

        public static final Presence NOBODY = new Presence(false, false, null);
    }

    /** "I am still here", from the screen on that till. */
    public void heard(String tillCode) {
        heardFrom.put(tillCode, Instant.now());
    }

    /** Lets a till go at once, rather than waiting for it to fall silent. */
    public void gone(String tillCode) {
        heardFrom.remove(tillCode);
    }

    /**
     * Signs a till out from the server, which is how a shop gets a place back from a machine that
     * was switched off or carried away without anybody logging out. Nothing else can: that machine
     * is gone, its session stays open behind it, and until the idle window has passed the place is
     * still spoken for - which on a shop set up for one counter means nobody else can bill at all.
     *
     * @return how many sessions were open on it, so the caller can say what it did
     */
    @Transactional
    public int signOut(String tillCode) {
        Instant now = Instant.now();
        List<AuthToken> open = tokens.findByTerminalCodeAndRevokedAtIsNull(tillCode);
        open.forEach(token -> token.revoke("RELEASED", now));
        gone(tillCode);
        return open.size();
    }

    /**
     * Every till somebody is signed in on, by code. One query for the whole shop: the settings
     * screen draws all of its tills at once.
     *
     * <p>Read oldest sign-in first, so when two people are signed in on one counter - a cashier
     * who walked off without signing out, and the one who took over - the name shown is the person
     * who signed in last, which is the person standing there.
     */
    @Transactional(readOnly = true)
    public Map<String, Presence> all() {
        Instant silentBefore = Instant.now().minus(SILENT_AFTER);
        Map<String, Presence> present = new HashMap<>();
        for (AuthToken token : tokens.liveSignIns()) {
            Instant seen = heardFrom.get(token.getTerminalCode());
            boolean answering = seen != null && seen.isAfter(silentBefore);
            present.put(token.getTerminalCode(),
                    new Presence(answering, true, token.getUser().getDisplayName()));
        }
        return present;
    }

    /** The till's state, for a till the shop may never have heard from. */
    public Presence of(String tillCode, Map<String, Presence> present) {
        return present.getOrDefault(tillCode, Presence.NOBODY);
    }

    /**
     * Every till somebody is signed in on, whether or not its screen has said anything lately.
     *
     * <p>This is the question a counter's <em>place</em> turns on, and it is deliberately not the
     * same question as "is somebody standing there". A counter whose screen has been quiet for
     * four minutes - asleep, off the Wi-Fi, part way through a reboot - must not have its place
     * given to another machine while its cashier is still signed in on it, or it comes back to a
     * shop that will not let it bill.
     */
    @Transactional(readOnly = true)
    public Set<String> signedIn() {
        return tokens.liveSignIns().stream()
                .map(AuthToken::getTerminalCode)
                .collect(Collectors.toSet());
    }
}
