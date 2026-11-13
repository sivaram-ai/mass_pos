package com.masspos.settings;

import com.masspos.auth.RequiresRole;
import com.masspos.client.ClientRegistry;
import com.masspos.client.CurrentCounter;
import com.masspos.client.TillPresence;
import com.masspos.client.TillPresence.Presence;
import com.masspos.common.Csv;
import com.masspos.common.IndiaTime;
import com.masspos.config.PosProperties;
import com.masspos.terminal.Terminal;
import com.masspos.terminal.TerminalRepository;
import com.masspos.terminal.TerminalType;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The settings screen: shop identity, GST details, receipt text, service contact, and the tills. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ShopSettingsService settings;
    private final TerminalRepository terminals;
    private final CurrentCounter counter;
    private final TillPresence presence;
    private final ClientRegistry clients;
    private final PosProperties pos;

    public SettingsController(ShopSettingsService settings, TerminalRepository terminals, CurrentCounter counter,
                              TillPresence presence, ClientRegistry clients, PosProperties pos) {
        this.settings = settings;
        this.terminals = terminals;
        this.counter = counter;
        this.presence = presence;
        this.clients = clients;
        this.pos = pos;
    }

    /**
     * @param readyToInvoice false while the shop's name or address is missing; the billing screen shows why
     * @param gstRegistered  false without a GSTIN: bills then charge no tax and are not tax invoices
     * @param thisTerminal   the till the browser asking is billing as: this machine on the server,
     *                       the counter's own till when a counter is asking
     * @param version        the build this till is running, so a shop reporting a problem can name it
     */
    public record SettingsView(ShopSettingsForm shop, boolean readyToInvoice, List<String> missingForInvoicing,
                               boolean gstRegistered, TerminalView thisTerminal, List<TerminalView> terminals,
                               String version) {
    }

    /**
     * @param active   false for a till the shop has retired; it stays listed because its code is on bills
     * @param inUse    somebody is signed in on it and its screen is still answering, right now
     * @param signedIn somebody is signed in, answering or not: what holds the till's place
     * @param staff    who that is, or null when nobody is signed in
     */
    /**
     * @param active    false for a till an admin has blocked; it stays listed because its code is on bills
     * @param overLimit the shop has more counters than its licence covers and this is one of the
     *                  extras, so nothing can bill on it
     */
    public record TerminalView(String id, String code, String name, String section, TerminalType type,
                               boolean active, boolean overLimit, Instant lastSeenAt, boolean inUse,
                               boolean signedIn, String staff) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public SettingsView get() {
        return viewOf(settings.current());
    }

    /**
     * @param places what pos.clients.max allows; 0 means this till keeps to itself
     * @param used   places a counter is signed in on
     * @param free   places another counter could still take
     */
    /**
     * @param places  what pos.clients.max allows: how many counters besides this server
     * @param counters how many the shop has, so the screen can stop offering to add another
     * @param roomForMore how many more it may create
     */
    public record TillsView(int places, int used, int free, int counters, int roomForMore,
                            List<TerminalView> tills) {
    }

    /**
     * The tills and how many places are left, for the panel that shows which ones somebody is
     * standing at. It has to be asked for again every few seconds to be worth anything, and
     * re-reading the whole settings would throw away whatever the person is part way through
     * typing into the form beside it.
     */
    @GetMapping("/tills")
    @Transactional(readOnly = true)
    public TillsView tills() {
        Map<String, Presence> present = presence.all();
        return new TillsView(clients.places(), clients.connected().size(), clients.free(),
                clients.counters().size(), clients.roomForMoreCounters(),
                terminals.findAllByOrderBySectionAscCodeAsc().stream()
                        .map(terminal -> viewOf(terminal, present))
                        .toList());
    }

    /** Changing these never touches invoices already issued: each carries its own copy. */
    @PutMapping
    @RequiresRole({UserRole.ADMIN})
    @Transactional
    public SettingsView update(@Valid @RequestBody ShopSettingsForm form) {
        return viewOf(settings.update(form));
    }

    /**
     * The shop's details as a sheet. Setting a second till up, or handing the lot to an accountant,
     * beats reading sixteen fields off one screen and typing them into another.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<String> export() {
        String csv = ShopSettingsCsv.write(viewOf(settings.current()).shop());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"shop-details-" + LocalDate.now(IndiaTime.ZONE) + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(Csv.EXCEL_BOM + csv);
    }

    /**
     * Reads a sheet into the form on screen and saves nothing. Whoever loaded it sees what arrived
     * and presses Save, which is where the GSTIN, CIN and FSSAI checks happen - the same place they
     * happen when the details are typed by hand, and the same place the errors are shown.
     */
    @PostMapping(value = "/import", consumes = "text/csv")
    @RequiresRole({UserRole.ADMIN})
    public ShopSettingsForm load(@RequestBody String csv) {
        return ShopSettingsCsv.read(csv);
    }
    private SettingsView viewOf(ShopSettings shop) {
        ShopSettingsForm form = ShopSettingsForm.of(shop);
        Map<String, Presence> present = presence.all();
        return new SettingsView(form, shop.isReadyToInvoice(), shop.missingForInvoicing(), shop.isGstRegistered(),
                thisTerminal(present),
                terminals.findAllByOrderBySectionAscCodeAsc().stream()
                        .map(terminal -> viewOf(terminal, present))
                        .toList(),
                pos.version());
    }

    /**
     * The machine looking at the screen, which with counters in the shop is not the machine holding
     * the database. This answered from {@code pos.properties} - the server's own identity - so every
     * counter was shown the server's name in the header and had "this machine" marked against the
     * server's row in the list of tills. It comes from the signed-in session instead, which is where
     * a counter's number has lived since it was first let in.
     *
     * <p>The name is the counter's own, as typed in its settings window and refreshed on the
     * {@code terminal} row at every sign-in. A counter that has somehow lost its row still gets its
     * code rather than the server's, because showing the wrong till is worse than showing a bare
     * code.
     */
    private TerminalView thisTerminal(Map<String, Presence> present) {
        PosProperties.Terminal me = pos.terminal();
        String here = counter.code();
        Presence at = presence.of(here, present);
        if (here.equals(me.code())) {
            // The server's own till is described from its own pos.properties, so it has no row to
            // take an id from until it has registered itself; its id is never acted on anyway.
            return new TerminalView(terminals.findByCode(me.code()).map(row -> row.getId().toString())
                    .orElse(""), me.code(), me.name(), me.section(), me.type(), true, false,
                    Instant.now(), at.inUse(), at.signedIn(), at.staff());
        }
        return terminals.findByCode(here)
                .map(terminal -> viewOf(terminal, present))
                .orElseGet(() -> new TerminalView("", here, here, "", TerminalType.COUNTER, true,
                        false, Instant.now(), at.inUse(), at.signedIn(), at.staff()));
    }

    private TerminalView viewOf(Terminal terminal, Map<String, Presence> present) {
        Presence at = presence.of(terminal.getCode(), present);
        return new TerminalView(terminal.getId().toString(), terminal.getCode(), terminal.getName(),
                terminal.getSection(), terminal.getType(), terminal.isActive(),
                clients.overLicenceLimit(terminal), terminal.getLastSeenAt(),
                at.inUse(), at.signedIn(), at.staff());
    }
}
