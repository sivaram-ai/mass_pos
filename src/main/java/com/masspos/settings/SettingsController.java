package com.masspos.settings;

import com.masspos.auth.RequiresRole;
import com.masspos.client.CurrentCounter;
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

/** The settings screen: shop identity, GST details, receipt text, service contact, and the tills. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ShopSettingsService settings;
    private final TerminalRepository terminals;
    private final CurrentCounter counter;
    private final PosProperties pos;

    public SettingsController(ShopSettingsService settings, TerminalRepository terminals, CurrentCounter counter,
                              PosProperties pos) {
        this.settings = settings;
        this.terminals = terminals;
        this.counter = counter;
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

    public record TerminalView(String code, String name, String section, TerminalType type, boolean active,
                               Instant lastSeenAt) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public SettingsView get() {
        return viewOf(settings.current());
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
        return new SettingsView(form, shop.isReadyToInvoice(), shop.missingForInvoicing(), shop.isGstRegistered(),
                thisTerminal(),
                terminals.findAllByOrderBySectionAscCodeAsc().stream().map(SettingsController::viewOf).toList(),
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
    private TerminalView thisTerminal() {
        PosProperties.Terminal me = pos.terminal();
        String here = counter.code();
        if (here.equals(me.code())) {
            return new TerminalView(me.code(), me.name(), me.section(), me.type(), true, Instant.now());
        }
        return terminals.findByCode(here)
                .map(SettingsController::viewOf)
                .orElseGet(() -> new TerminalView(here, here, "", TerminalType.COUNTER, true, Instant.now()));
    }

    private static TerminalView viewOf(Terminal terminal) {
        return new TerminalView(terminal.getCode(), terminal.getName(), terminal.getSection(), terminal.getType(),
                terminal.isActive(), terminal.getLastSeenAt());
    }
}
