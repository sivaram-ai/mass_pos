package com.masspos.settings;

import com.masspos.billing.SellerDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopSettingsService {

    private static final int SINGLETON = 1;

    private final ShopSettingsRepository repository;

    public ShopSettingsService(ShopSettingsRepository repository) {
        this.repository = repository;
    }

    /** The shop's one settings row. Created empty on first start, then filled in on screen. */
    @Transactional
    public ShopSettings current() {
        return repository.findBySingleton(SINGLETON).orElseGet(() -> repository.save(new ShopSettings()));
    }

    /**
     * Creates the settings row on the very first start, taking whatever an installer pre-filled in
     * {@code pos.properties}. Does nothing once the row exists: after that the screen owns it.
     */
    @Transactional
    public ShopSettings seedOnce(ShopSettingsForm seed) {
        return repository.findBySingleton(SINGLETON).orElseGet(() -> {
            ShopSettings created = new ShopSettings();
            created.apply(seed);
            return repository.save(created);
        });
    }

    /**
     * The settings as they stand, without creating anything. Safe inside a read-only transaction,
     * which is where printing and billing read them from.
     */
    @Transactional(readOnly = true)
    public ShopSettings currentOrEmpty() {
        return repository.findBySingleton(SINGLETON).orElseGet(ShopSettings::new);
    }

    @Transactional
    public ShopSettings update(ShopSettingsForm form) {
        ShopSettings settings = current();
        settings.apply(form);
        return settings;
    }

    /**
     * The seller as it goes onto an invoice being issued now.
     *
     * @throws IllegalStateException while the shop's name or address is still missing (HTTP 409)
     */
    @Transactional(readOnly = true)
    public SellerDetails sellerSnapshot() {
        return SellerDetails.from(repository.findBySingleton(SINGLETON).orElseGet(ShopSettings::new));
    }
}
