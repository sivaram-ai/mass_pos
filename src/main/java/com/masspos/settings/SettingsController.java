package com.masspos.settings;

import com.masspos.auth.RequiresRole;
import com.masspos.config.PosProperties;
import com.masspos.terminal.TerminalRepository;
import com.masspos.terminal.TerminalType;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The settings screen: shop identity, GST details, receipt text, service contact, and the tills. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ShopSettingsService settings;
    private final TerminalRepository terminals;
    private final PosProperties pos;

    public SettingsController(ShopSettingsService settings, TerminalRepository terminals, PosProperties pos) {
        this.settings = settings;
        this.terminals = terminals;
        this.pos = pos;
    }

    /**
     * @param readyToInvoice false while the shop's name or address is missing; the billing screen shows why
     * @param gstRegistered  false without a GSTIN: bills then charge no tax and are not tax invoices
     * @param thisTerminal   identity of the machine being used, which lives in its own pos.properties
     */
    public record SettingsView(ShopSettingsForm shop, boolean readyToInvoice, List<String> missingForInvoicing,
                               boolean gstRegistered, TerminalView thisTerminal, List<TerminalView> terminals) {
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

    private SettingsView viewOf(ShopSettings shop) {
        ShopSettingsForm form = new ShopSettingsForm(shop.getLegalName(), shop.getTradeName(), shop.getGstin(),
                shop.addressLines(), shop.getPhone(), shop.getEmail(), shop.getWebsite(), shop.getCin(),
                shop.getFssaiLicense(), shop.getUpiVpa(), shop.receiptFooter(), shop.getServiceProviderName(),
                shop.getServicePhone(), shop.getServiceEmail(), shop.isRoundInvoiceTotal(),
                shop.isBlockNegativeStock());
        PosProperties.Terminal me = pos.terminal();
        return new SettingsView(form, shop.isReadyToInvoice(), shop.missingForInvoicing(), shop.isGstRegistered(),
                new TerminalView(me.code(), me.name(), me.section(), me.type(), true, Instant.now()),
                terminals.findAllByOrderBySectionAscCodeAsc().stream()
                        .map(terminal -> new TerminalView(terminal.getCode(), terminal.getName(),
                                terminal.getSection(), terminal.getType(), terminal.isActive(),
                                terminal.getLastSeenAt()))
                        .toList());
    }
}
