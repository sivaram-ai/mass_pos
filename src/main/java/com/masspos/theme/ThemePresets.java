package com.masspos.theme;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The themes that ship with the product: the look it has always had, and eight designed ones read
 * from {@code themes/presets.json}. They are the server's, never the client's - applying one stores
 * the server's copy under its id, so a browser cannot call a poor palette "high-contrast".
 */
public final class ThemePresets {

    /**
     * The colours the screens have always been painted in (Tailwind's sky, slate and emerald). The
     * screens apply nothing at all for this theme, so it is exactly what was shipped; these values
     * are what the builder shows and starts from.
     */
    public static final ThemeDefinition DEFAULT = new ThemeDefinition("default", "Default",
            "The original GO RockIt look: slate header, sky-blue actions.",
            colors("#0284c7", "#ffffff", "#1e293b", "#ffffff", "#6ee7b7",
                    "#f1f5f9", "#ffffff", "#0f172a", "#0f172a", "#f1f5f9", "#334155"));

    private static final List<ThemeDefinition> ALL = load();

    private ThemePresets() {
    }

    /** The default first, then the designed ones. */
    public static List<ThemeDefinition> all() {
        return ALL;
    }

    public static Optional<ThemeDefinition> byId(String id) {
        return ALL.stream().filter(theme -> theme.id().equals(id)).findFirst();
    }

    private static Map<String, String> colors(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < ThemeRules.KEYS.size(); i++) {
            map.put(ThemeRules.KEYS.get(i), values[i]);
        }
        return map;
    }

    private static List<ThemeDefinition> load() {
        try (InputStream in = new ClassPathResource("themes/presets.json").getInputStream()) {
            List<ThemeDefinition> designed = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
            List<ThemeDefinition> all = new ArrayList<>();
            all.add(DEFAULT);
            all.addAll(designed);
            return List.copyOf(all);
        } catch (IOException cannotRead) {
            throw new UncheckedIOException("themes/presets.json is missing from the build", cannotRead);
        }
    }
}
