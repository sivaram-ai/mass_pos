package com.masspos.license;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fingerprints the computer from IDs that survive a restart but not a copy:
 *
 * <ul>
 *   <li><b>Windows</b>: the motherboard's SMBIOS UUID ({@code HKLM\SYSTEM\HardwareConfig LastConfig}) and
 *       the Windows installation's {@code MachineGuid}. A disk cloned into another PC keeps the
 *       MachineGuid but not the motherboard, so its ID changes. Reinstalling Windows or replacing the
 *       motherboard also changes it, and the vendor issues a new licence.</li>
 *   <li><b>macOS</b>: the IOPlatformUUID. <b>Linux</b>: /etc/machine-id.</li>
 * </ul>
 *
 * <p>Read with {@code reg.exe}, not PowerShell or WMI: it answers in tens of milliseconds and is on
 * every Windows edition. Read once, then kept for the life of the process.
 */
@Component
public class OsMachineIdentity implements MachineIdentity {

    private static final Logger log = LoggerFactory.getLogger(OsMachineIdentity.class);

    /** Readable over the phone: no I, L, O or U. */
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final String SALT = "masspos-machine-v1";
    private static final Pattern IOREG_UUID = Pattern.compile("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"");

    private volatile String cached;

    @Override
    public String machineId() {
        String id = cached;
        if (id == null) {
            id = fingerprint(readParts());
            cached = id;
        }
        return id;
    }

    private static List<String> readParts() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return List.of(
                    registry("HKLM\\SYSTEM\\HardwareConfig", "LastConfig"),
                    registry("HKLM\\SOFTWARE\\Microsoft\\Cryptography", "MachineGuid"));
        }
        if (os.startsWith("mac")) {
            String uuid = ioregPlatformUuid(run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"));
            if (uuid == null) {
                throw new IllegalStateException("this computer did not report it");
            }
            return List.of(uuid);
        }
        for (Path file : List.of(Path.of("/etc/machine-id"), Path.of("/var/lib/dbus/machine-id"))) {
            try {
                if (Files.isReadable(file)) {
                    return List.of(Files.readString(file));
                }
            } catch (IOException e) {
                // Try the next place a machine ID is kept.
            }
        }
        throw new IllegalStateException("this computer does not keep one");
    }

    private static String registry(String key, String name) {
        String value = registryValue(run("reg", "query", key, "/v", name), name);
        if (value == null) {
            // Which registry key is missing matters to whoever is called out, not to the shop.
            log.warn("Windows did not report the value {} under {}", name, key);
            throw new IllegalStateException("Windows did not report it");
        }
        return value;
    }

    /** The value of {@code name} in the output of {@code reg query <key> /v <name>}, or null. */
    static String registryValue(String regQueryOutput, String name) {
        Matcher line = Pattern.compile("^\\s*" + Pattern.quote(name) + "\\s+REG_\\w+\\s+(.+?)\\s*$", Pattern.MULTILINE)
                .matcher(regQueryOutput);
        return line.find() ? line.group(1) : null;
    }

    static String ioregPlatformUuid(String ioregOutput) {
        Matcher uuid = IOREG_UUID.matcher(ioregOutput);
        return uuid.find() ? uuid.group(1) : null;
    }

    /**
     * 80 bits of SHA-256 over the normalised parts, as 16 Crockford base32 characters in fours. Braces,
     * case and surrounding spaces are ignored, since the registry and WMI format the same UUID differently.
     */
    static String fingerprint(List<String> parts) {
        List<String> normalised = new ArrayList<>();
        for (String part : parts) {
            String value = part == null ? "" : part.strip().replace("{", "").replace("}", "").toUpperCase(Locale.ROOT);
            if (value.isEmpty()) {
                throw new IllegalStateException("it came back empty");
            }
            normalised.add(value);
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((SALT + "\n" + String.join("\n", normalised)).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder id = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < 10; i++) {
            buffer = (buffer << 8) | (digest[i] & 0xff);
            bits += 8;
            while (bits >= 5) {
                if (id.length() == 4 || id.length() == 9 || id.length() == 14) {
                    id.append('-');
                }
                id.append(CROCKFORD.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        return id.toString();
    }

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            // The answers are a few lines, well inside the pipe buffer, so waiting first cannot deadlock.
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Windows would not answer");
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The command and what it said go to the log. On screen this clause lands inside a
            // sentence on the Licence screen, read by a shop owner who has no use for either.
            log.warn("Could not run {} to read the hardware ID", command[0], e);
            throw new IllegalStateException("Windows would not answer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reading it was interrupted");
        }
    }
}
