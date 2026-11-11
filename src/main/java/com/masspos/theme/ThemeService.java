package com.masspos.theme;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** The shop's theme: what is in force, and the custom themes kept for reuse. */
@Service
public class ThemeService {

    /** Enough for any shop; keeps one row's JSON from growing without end. */
    static final int LIBRARY_LIMIT = 24;
    private static final int SINGLETON = 1;

    /**
     * @param revision counters poll this and fetch the rest only when it moves
     * @param active   the theme every screen wears
     * @param presets  the built-in themes, default first
     * @param library  custom themes saved for reuse
     */
    public record ThemeView(long revision, ThemeDefinition active, List<ThemeDefinition> presets,
                            List<ThemeDefinition> library) {
    }

    private final ThemeSettingsRepository repository;
    private final ObjectMapper json;

    public ThemeService(ThemeSettingsRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public ThemeView view() {
        return viewOf(current());
    }

    @Transactional
    public long revision() {
        return current().getRevision();
    }

    /**
     * Puts a theme in force for the whole shop. A built-in is taken from the server's own copy
     * whatever the caller sent; anything else is a custom theme and has to be well-formed and readable.
     */
    @Transactional
    public ThemeView apply(ThemeDefinition requested) {
        ThemeSettings row = current();
        ThemeDefinition chosen = ThemePresets.byId(requested == null ? null : requested.id())
                .orElseGet(() -> ThemeRules.validated(requested));
        row.change(write(chosen), row.getLibraryJson());
        return viewOf(row);
    }

    /** Saves a custom theme for reuse, replacing the one with the same id. */
    @Transactional
    public ThemeView save(ThemeDefinition requested) {
        ThemeDefinition theme = ThemeRules.validated(requested);
        ThemeSettings row = current();
        List<ThemeDefinition> library = new ArrayList<>(readLibrary(row));
        library.removeIf(saved -> saved.id().equals(theme.id()));
        if (library.size() >= LIBRARY_LIMIT) {
            throw new IllegalArgumentException("The shop can keep " + LIBRARY_LIMIT
                    + " themes of its own. Delete one you no longer use first.");
        }
        library.add(theme);
        row.change(row.getActiveJson(), write(library));
        return viewOf(row);
    }

    @Transactional
    public ThemeView delete(String id) {
        ThemeSettings row = current();
        List<ThemeDefinition> library = new ArrayList<>(readLibrary(row));
        if (!library.removeIf(saved -> saved.id().equals(id))) {
            throw new EntityNotFoundException("There is no saved theme with that id.");
        }
        row.change(row.getActiveJson(), write(library));
        return viewOf(row);
    }

    private ThemeSettings current() {
        return repository.findBySingleton(SINGLETON).orElseGet(() -> repository.save(new ThemeSettings()));
    }

    private ThemeView viewOf(ThemeSettings row) {
        return new ThemeView(row.getRevision(), readActive(row), ThemePresets.all(), readLibrary(row));
    }

    private ThemeDefinition readActive(ThemeSettings row) {
        if (row.getActiveJson().isBlank()) {
            return ThemePresets.DEFAULT;
        }
        try {
            ThemeDefinition stored = json.readValue(row.getActiveJson(), ThemeDefinition.class);
            // A built-in is always the server's copy, so a preset improved in a release reaches shops
            // that picked it last year.
            return ThemePresets.byId(stored.id()).orElse(stored);
        } catch (JsonProcessingException unreadable) {
            return ThemePresets.DEFAULT;
        }
    }

    private List<ThemeDefinition> readLibrary(ThemeSettings row) {
        try {
            return json.readValue(row.getLibraryJson(), new TypeReference<List<ThemeDefinition>>() {
            });
        } catch (JsonProcessingException unreadable) {
            return List.of();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException cannotWrite) {
            throw new IllegalStateException("Could not store the theme.", cannotWrite);
        }
    }
}
