package com.masspos.theme;

import com.masspos.auth.RequiresRole;
import com.masspos.user.UserRole;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The shop's colour scheme. Everybody signed in reads it - every counter has to wear it - and only
 * an administrator changes it. Changes are not pushed: each screen asks {@code /revision} every few
 * seconds, a few bytes, and fetches the whole theme when the number has moved.
 */
@RestController
@RequestMapping("/api/theme")
public class ThemeController {

    private final ThemeService themes;

    public ThemeController(ThemeService themes) {
        this.themes = themes;
    }

    @GetMapping
    public ThemeService.ThemeView get() {
        return themes.view();
    }

    @GetMapping("/revision")
    public Map<String, Long> revision() {
        return Map.of("revision", themes.revision());
    }

    /** Puts a theme in force on every counter. */
    @PutMapping("/active")
    @RequiresRole({UserRole.ADMIN})
    public ThemeService.ThemeView apply(@RequestBody ThemeDefinition theme) {
        return themes.apply(theme);
    }

    /** Keeps a custom theme in the shop's library, replacing the one with the same id. */
    @PutMapping("/library")
    @RequiresRole({UserRole.ADMIN})
    public ThemeService.ThemeView save(@RequestBody ThemeDefinition theme) {
        return themes.save(theme);
    }

    @DeleteMapping("/library/{id}")
    @RequiresRole({UserRole.ADMIN})
    public ThemeService.ThemeView delete(@PathVariable String id) {
        return themes.delete(id);
    }
}
