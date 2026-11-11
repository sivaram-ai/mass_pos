package com.masspos.theme;

import java.util.Map;

/**
 * One colour scheme. {@code colors} holds exactly the keys in {@link ThemeRules#KEYS}, each a
 * {@code #rrggbb} string; everything else the screens paint (the tints of the primary colour, borders,
 * muted text, the navigation's own greys) is derived from these on the screen, so a theme stays eleven
 * numbers that fit in a text file someone can send to another shop.
 *
 * @param id          {@code default} and the built-in presets are the server's own; a theme somebody
 *                    made is {@code custom-<letters and digits>}
 * @param description one line for the picker; empty for a custom theme
 */
public record ThemeDefinition(String id, String name, String description, Map<String, String> colors) {
}
