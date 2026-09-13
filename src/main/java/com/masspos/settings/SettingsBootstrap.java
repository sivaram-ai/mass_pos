package com.masspos.settings;

import com.masspos.billing.BillingProperties;
import com.masspos.billing.CompanyProperties;
import com.masspos.config.PosProperties;
import com.masspos.receipt.ReceiptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates the shop's settings row on first start, taking anything an installer pre-filled in
 * {@code pos.properties}, and warns while the minimum for invoicing is still missing. From then on
 * the database is the source of truth and the settings screen edits it.
 */
@Component
@Order(40)
public class SettingsBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettingsBootstrap.class);

    private final ShopSettingsService settings;
    private final CompanyProperties company;
    private final ReceiptProperties receipt;
    private final BillingProperties billing;
    private final PosProperties pos;

    public SettingsBootstrap(ShopSettingsService settings, CompanyProperties company, ReceiptProperties receipt,
                             BillingProperties billing, PosProperties pos) {
        this.settings = settings;
        this.company = company;
        this.receipt = receipt;
        this.billing = billing;
        this.pos = pos;
    }

    @Override
    public void run(ApplicationArguments args) {
        ShopSettings shop = settings.seedOnce(new ShopSettingsForm(company.legalName(), company.tradeName(),
                company.gstin(), company.address(), company.phone(), company.email(), company.website(),
                company.cin(), company.fssaiLicense(), company.upiVpa(), receipt.footer(), "", "", "",
                billing.roundInvoiceTotal(), billing.blockNegativeStock()));

        List<String> missing = shop.missingForInvoicing();
        if (missing.isEmpty()) {
            log.info("Billing as {} (GSTIN {})", shop.displayName(), shop.getGstin());
        } else {
            log.warn("Invoices cannot be issued until the shop's {} are filled in on the settings screen"
                    + " (or in {}/pos.properties before the first start)", String.join(", ", missing), pos.dataDir());
        }
    }
}
