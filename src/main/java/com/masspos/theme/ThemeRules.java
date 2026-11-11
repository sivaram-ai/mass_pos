package com.masspos.theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a theme has to be before the whole shop is shown it: complete, well-formed, and readable.
 *
 * <p>Readability is enforced here and not only in the builder, because the builder is a convenience
 * and the server is what every counter obeys. A screen that cannot be read at a till with a queue in
 * front of it is a failure of the product, whoever picked the colours. The floor is WCAG 2.1 level
 * AA - 4.5:1 for text, 3:1 for the primary colour used as a control - checked on the pairs the
 * screens actually put together.
 *
 * <p>The built-in {@code default} theme is the look the product shipped with and is exempt: white on
 * its sky-blue button is 4.1:1. It is the server's own copy, so nobody can submit a "default" that
 * is anything else.
 */
public final class ThemeRules {

    /** Every colour a theme sets, in the order the builder lists them. */
    public static final List<String> KEYS = List.of(
            "primary", "onPrimary", "secondary", "onSecondary", "accent",
            "background", "surface", "text", "nav", "navText", "navActive");

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern CUSTOM_ID = Pattern.compile("custom-[a-z0-9]{1,32}");

    /** A foreground that has to be readable on a background, and how readable. */
    public record Pair(String foreground, String background, double minimum, String meaning) {
    }

    public static final List<Pair> PAIRS = List.of(
            new Pair("text", "background", 4.5, "Text on the page background"),
            new Pair("text", "surface", 4.5, "Text on cards and dialogs"),
            new Pair("onPrimary", "primary", 4.5, "Text on primary buttons"),
            new Pair("onSecondary", "secondary", 4.5, "Text on secondary buttons and selected tabs"),
            new Pair("navText", "nav", 4.5, "Text on the header and menu"),
            new Pair("navText", "navActive", 4.5, "Text on the open menu item"),
            new Pair("primary", "surface", 3.0, "Primary colour as links and controls on cards"),
            new Pair("accent", "nav", 3.0, "Highlights on the header and menu"));

    private ThemeRules() {
    }

    /** True for a custom theme's id; the built-ins have their own ids and are never submitted. */
    public static boolean isCustomId(String id) {
        return id != null && CUSTOM_ID.matcher(id).matches();
    }

    /**
     * The theme as it will be stored: trimmed, colours lower-cased.
     *
     * @throws IllegalArgumentException with a sentence for the person who built it
     */
    public static ThemeDefinition clean(ThemeDefinition raw) {
        if (raw == null || raw.colors() == null) {
            throw new IllegalArgumentException("That is not a theme: it has no colours.");
        }
        if (!isCustomId(raw.id())) {
            throw new IllegalArgumentException("A theme of your own needs an id like custom-ab12, not \""
                    + raw.id() + "\".");
        }
        String name = raw.name() == null ? "" : raw.name().replaceAll("\\p{Cntrl}", " ").trim();
        if (name.isEmpty() || name.length() > 40) {
            throw new IllegalArgumentException("Give the theme a name of up to 40 characters.");
        }
        String description = raw.description() == null ? ""
                : raw.description().replaceAll("\\p{Cntrl}", " ").trim();
        if (description.length() > 200) {
            description = description.substring(0, 200);
        }
        Map<String, String> colors = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = raw.colors().get(key);
            if (value == null || !HEX.matcher(value.trim()).matches()) {
                throw new IllegalArgumentException("The colour \"" + key + "\" has to be a six-digit hex value like"
                        + " #1a59d3" + (value == null ? ", and it is missing." : ", not \"" + value + "\"."));
            }
            colors.put(key, value.trim().toLowerCase(Locale.ROOT));
        }
        return new ThemeDefinition(raw.id(), name, description, colors);
    }

    /** Every pair that falls short of its floor, each as a sentence. Empty when the theme can be read. */
    public static List<String> readabilityProblems(Map<String, String> colors) {
        List<String> problems = new ArrayList<>();
        for (Pair pair : PAIRS) {
            double ratio = contrast(colors.get(pair.foreground()), colors.get(pair.background()));
            if (ratio < pair.minimum()) {
                problems.add(String.format(Locale.ROOT, "%s is %.1f:1 and needs at least %.1f:1.",
                        pair.meaning(), ratio, pair.minimum()));
            }
        }
        return problems;
    }

    /** A custom theme, cleaned and proven readable. */
    public static ThemeDefinition validated(ThemeDefinition raw) {
        ThemeDefinition theme = clean(raw);
        List<String> problems = readabilityProblems(theme.colors());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("This theme is too hard to read: " + String.join(" ", problems));
        }
        return theme;
    }

    /** WCAG 2.1 contrast ratio, 1 to 21. */
    public static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    public static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xff) + 0.7152 * channel((rgb >> 8) & 0xff)
                + 0.0722 * channel(rgb & 0xff);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
