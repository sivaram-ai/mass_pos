package com.masspos.hardware;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * This till's printer settings: seeded once from {@code pos.properties}, edited on the settings
 * screen after that.
 *
 * <p>{@link #active()} is read on the printing path, so it answers from a cached copy rather than
 * the database. Saving replaces that copy, and {@link PrinterConnections} notices the change the
 * next time it is asked for a connection.
 */
@Service
public class PrinterSettingsService {

    private static final int SINGLETON = 1;

    private final PrinterSettingsRepository repository;
    private final PrinterProperties fromFile;
    private volatile PrinterProperties active;

    public PrinterSettingsService(PrinterSettingsRepository repository, PrinterProperties fromFile) {
        this.repository = repository;
        this.fromFile = fromFile;
    }

    /**
     * The printer in force right now. Never touches the database once warm, so a bill is never held
     * up by a query, and falls back to {@code pos.properties} before the row exists.
     */
    public PrinterProperties active() {
        PrinterProperties cached = active;
        if (cached != null) {
            return cached;
        }
        PrinterProperties loaded = repository.findBySingleton(SINGLETON)
                .map(settings -> settings.toForm().toProperties())
                .orElse(fromFile);
        active = loaded;
        return loaded;
    }

    /** The row the screen edits, created from {@code pos.properties} if it is somehow missing. */
    @Transactional
    public PrinterSettings current() {
        return repository.findBySingleton(SINGLETON).orElseGet(() -> create(PrinterSettingsForm.from(fromFile)));
    }

    /**
     * Creates the row on the very first start from whatever is in {@code pos.properties}. Does
     * nothing once it exists: from then on the screen owns the printer, not the file.
     */
    @Transactional
    public PrinterSettings seedOnce(PrinterSettingsForm seed) {
        return repository.findBySingleton(SINGLETON).orElseGet(() -> create(seed));
    }

    @Transactional
    public PrinterSettings update(PrinterSettingsForm form) {
        PrinterSettings settings = current();
        settings.apply(form);
        active = form.toProperties();
        return settings;
    }

    private PrinterSettings create(PrinterSettingsForm form) {
        PrinterSettings created = new PrinterSettings();
        created.apply(form);
        active = form.toProperties();
        return repository.save(created);
    }
}
