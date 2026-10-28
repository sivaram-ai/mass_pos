package com.masspos.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Opens a till's screen in a window that looks like an application rather than a browser tab.
 *
 * <p>Edge or Chrome in {@code --app} mode: no address bar, no tabs, its own taskbar button. That
 * keeps the installers the size they are and uses a browser engine Windows already keeps patched.
 * With neither installed the default browser opens instead, which still works, just with the
 * browser's own furniture around it.
 *
 * <p>Shared by the server's shortcut and by a counter, because they want exactly the same window
 * and a second copy of this list would be a second thing to keep current.
 */
public final class Browser {

    private static final List<String> APP_BROWSERS = List.of(
            "%ProgramFiles(x86)%/Microsoft/Edge/Application/msedge.exe",
            "%ProgramFiles%/Microsoft/Edge/Application/msedge.exe",
            "%LOCALAPPDATA%/Microsoft/Edge/Application/msedge.exe",
            "%ProgramFiles%/Google/Chrome/Application/chrome.exe",
            "%ProgramFiles(x86)%/Google/Chrome/Application/chrome.exe",
            "%LOCALAPPDATA%/Google/Chrome/Application/chrome.exe");

    private Browser() {
    }

    public static void openApp(String url) throws IOException {
        for (String candidate : APP_BROWSERS) {
            Path browser = expand(candidate);
            if (browser != null && Files.isExecutable(browser)) {
                new ProcessBuilder(browser.toString(), "--app=" + url)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return;
            }
        }
        Desktop.getDesktop().browse(URI.create(url));
    }

    /** Resolves one {@code %VARIABLE%} at the front of a path, the way cmd would. */
    public static Path expand(String path) {
        if (!path.startsWith("%")) {
            return Path.of(path);
        }
        int end = path.indexOf('%', 1);
        String value = System.getenv(path.substring(1, end));
        return value == null || value.isBlank() ? null : Path.of(value + path.substring(end + 1));
    }
}
