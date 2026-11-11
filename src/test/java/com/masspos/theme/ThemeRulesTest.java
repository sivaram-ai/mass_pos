package com.masspos.theme;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a theme has to be before every counter in the shop is shown it. The designed themes are
 * checked here against the same floor a shop's own themes are held to: a preset that cannot be read
 * would ship to every till.
 */
class ThemeRulesTest {

    @Test
    void thereAreEightDesignedThemesBesideTheDefault() {
        assertThat(ThemePresets.all()).extracting(ThemeDefinition::id).containsExactly(
                "default", "light", "dark", "modern-blue", "emerald-green", "royal-red", "purple-luxe",
                "minimal-grey", "high-contrast");
    }

    @Test
    void everyDesignedThemeIsCompleteAndReadableAtLevelAA() {
        for (ThemeDefinition preset : ThemePresets.all()) {
            assertThat(preset.colors().keySet()).as(preset.id()).containsExactlyElementsOf(ThemeRules.KEYS);
            if (!preset.id().equals("default")) {
                assertThat(ThemeRules.readabilityProblems(preset.colors())).as(preset.id()).isEmpty();
            }
        }
    }

    /** The one measured against the original look: kept as shipped, so it is the exemption. */
    @Test
    void theDefaultIsTheOriginalLookAndTheOnlyOneBelowTheFloor() {
        assertThat(ThemeRules.readabilityProblems(ThemePresets.DEFAULT.colors()))
                .hasSize(1).first().asString().contains("primary buttons");
    }

    @Test
    void highContrastReachesLevelAAA() {
        Map<String, String> colors = ThemePresets.byId("high-contrast").orElseThrow().colors();

        assertThat(ThemeRules.contrast(colors.get("text"), colors.get("background"))).isGreaterThanOrEqualTo(7.0);
        assertThat(ThemeRules.contrast(colors.get("onPrimary"), colors.get("primary"))).isGreaterThanOrEqualTo(7.0);
        assertThat(ThemeRules.contrast(colors.get("navText"), colors.get("nav"))).isGreaterThanOrEqualTo(7.0);
    }

    @Test
    void measuresContrastTheWayWcagDoes() {
        assertThat(ThemeRules.contrast("#000000", "#ffffff")).isEqualTo(21.0);
        assertThat(ThemeRules.contrast("#ffffff", "#ffffff")).isEqualTo(1.0);
        assertThat(ThemeRules.contrast("#777777", "#ffffff")).isBetween(4.4, 4.6);
    }

    @Test
    void refusesAThemeWhoseTextCannotBeRead() {
        Map<String, String> colors = new LinkedHashMap<>(ThemePresets.byId("light").orElseThrow().colors());
        colors.put("text", "#e5e7eb");

        assertThatThrownBy(() -> ThemeRules.validated(custom("custom-pale", colors)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too hard to read")
                .hasMessageContaining("Text on cards and dialogs");
    }

    @Test
    void refusesAColourThatIsNotSixHexDigits() {
        Map<String, String> colors = new LinkedHashMap<>(ThemePresets.byId("light").orElseThrow().colors());
        colors.put("primary", "blue");

        assertThatThrownBy(() -> ThemeRules.validated(custom("custom-blue", colors)))
                .hasMessageContaining("\"primary\"").hasMessageContaining("#1a59d3");
    }

    @Test
    void refusesAThemeMissingAColour() {
        Map<String, String> colors = new LinkedHashMap<>(ThemePresets.byId("light").orElseThrow().colors());
        colors.remove("nav");

        assertThatThrownBy(() -> ThemeRules.validated(custom("custom-short", colors)))
                .hasMessageContaining("\"nav\"").hasMessageContaining("missing");
    }

    @Test
    void aCustomThemeCannotTakeABuiltInsId() {
        Map<String, String> colors = ThemePresets.byId("light").orElseThrow().colors();

        assertThatThrownBy(() -> ThemeRules.validated(custom("dark", colors)))
                .hasMessageContaining("custom-");
    }

    @Test
    void tidiesWhatItKeeps() {
        Map<String, String> colors = new LinkedHashMap<>(ThemePresets.byId("light").orElseThrow().colors());
        colors.put("primary", " #2563EB ");

        ThemeDefinition kept = ThemeRules.validated(custom("custom-tidy", colors));

        assertThat(kept.colors().get("primary")).isEqualTo("#2563eb");
        assertThat(kept.name()).isEqualTo("Mine");
    }

    private static ThemeDefinition custom(String id, Map<String, String> colors) {
        return new ThemeDefinition(id, " Mine\t", null, colors);
    }
}
