package com.masspos.desktop;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * What the shop's shortcut runs. The till's server is a background process with no window of its
 * own, so clicking the icon has to do three things before anyone sees a bill: find out whether the
 * server is already up, start it if it is not, and then open the screen in a window that looks like
 * an application rather than a browser tab.
 *
 * <p>It is packaged as its own small jar and launched by its own exe, so it starts in well under a
 * second while the server takes its time behind it. Clicking the icon again with the till already
 * running costs one connect to the port and simply opens another window.
 *
 * <p>The window is Edge (or Chrome) in {@code --app} mode: no address bar, no tabs, its own taskbar
 * button. That keeps the installer at the size it is and uses a browser engine Windows already
 * keeps patched. If neither is installed the default browser is opened instead, which still works,
 * just with the browser's own chrome around it.
 */
public final class DesktopLauncher {

    /** The added jpackage launcher that runs Spring Boot; it sits beside this one. */
    private static final String SERVER_EXE = "GoRockitPOS-Server.exe";

    /** A cold start on a shop PC is several seconds; a slow disk and antivirus can treble that. */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Set on the server process this launcher starts. A launcher that finds it in its own
     * environment has been packaged as the server by mistake, and starting another one would loop:
     * one clear message beats filling a till's memory with half-started servers.
     */
    private static final String STARTED_BY_SHELL = "GOROCKIT_STARTED_BY_SHELL";

    private static final List<String> APP_BROWSERS = List.of(
            "%ProgramFiles(x86)%/Microsoft/Edge/Application/msedge.exe",
            "%ProgramFiles%/Microsoft/Edge/Application/msedge.exe",
            "%LOCALAPPDATA%/Microsoft/Edge/Application/msedge.exe",
            "%ProgramFiles%/Google/Chrome/Application/chrome.exe",
            "%ProgramFiles(x86)%/Google/Chrome/Application/chrome.exe",
            "%LOCALAPPDATA%/Google/Chrome/Application/chrome.exe");

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        try {
            refuseIfStartedAsTheServer(System.getenv(STARTED_BY_SHELL));
            int port = port();
            if (!isUp(port)) {
                startServer();
                waitUntilUp(port);
            }
            open("http://127.0.0.1:" + port + "/");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "GO RockIt", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    /**
     * The port the server will answer on: whatever the till set in its own pos.properties, else the
     * default every other till uses. Read the same way application.properties resolves it.
     */
    static int port() {
        Path settings = PosPaths.dataDir().resolve("pos.properties");
        if (Files.isReadable(settings)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(settings)) {
                properties.load(in); // ISO-8859-1, as Boot reads it
                String port = properties.getProperty("server.port");
                if (port != null && !port.isBlank()) {
                    return Integer.parseInt(port.trim());
                }
            } catch (IOException | NumberFormatException ignored) {
                // A settings file we cannot read is the server's problem to report, not ours.
            }
        }
        String fromEnvironment = System.getenv("POS_PORT");
        return fromEnvironment == null || fromEnvironment.isBlank()
                ? PosPaths.DEFAULT_PORT
                : Integer.parseInt(fromEnvironment.trim());
    }

    /**
     * Tomcat binds the port as the last thing it does before the application is ready, so something
     * accepting a connection here is a till that can serve the screen.
     */
    static boolean isUp(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException notYet) {
            return false;
        }
    }


    static void refuseIfStartedAsTheServer(String marker) throws IOException {
        if (marker != null) {
            throw new IOException("GO RockIt is not installed properly: " + SERVER_EXE
                    + " is running the desktop launcher instead of the till itself."
                    + " Install it again from the GO RockIt installer.");
        }
    }
    private static void startServer() throws IOException {
        Path server = appDir().resolve(SERVER_EXE);
        if (Files.notExists(server)) {
            throw new IOException("GO RockIt is not installed properly: " + server + " is missing."
                    + " Install it again from the GO RockIt installer.");
        }
        ProcessBuilder till = new ProcessBuilder(server.toString())
                .directory(appDir().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        till.environment().put(STARTED_BY_SHELL, "1");
        till.start();
    }

    private static void waitUntilUp(int port) throws InterruptedException, IOException {
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (isUp(port)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IOException("GO RockIt did not start within " + START_TIMEOUT.toSeconds()
                + " seconds. Look in " + PosPaths.dataDir().resolve("logs").resolve("pos.log")
                + " for the reason.");
    }

    /** Opens the till's screen in a window with no browser furniture around it. */
    private static void open(String url) throws IOException {
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
    static Path expand(String path) {
        if (!path.startsWith("%")) {
            return Path.of(path);
        }
        int end = path.indexOf('%', 1);
        String value = System.getenv(path.substring(1, end));
        return value == null || value.isBlank() ? null : Path.of(value + path.substring(end + 1));
    }

    private static Path appDir() {
        String launcher = System.getProperty("jpackage.app-path");
        return launcher == null ? Path.of("").toAbsolutePath() : Path.of(launcher).getParent();
    }
}
