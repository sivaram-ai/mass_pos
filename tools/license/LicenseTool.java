import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Mass POS licence tool, for the vendor only. It holds the private signing key's workflow: never ship
 * this folder's output, the private key or the licence register with the app.
 *
 * <p>Needs only a JDK (21+), no build:
 * <pre>
 *   java tools/license/LicenseTool.java keygen  --dir C:\MassPOS-licensing
 *   java tools/license/LicenseTool.java issue   --dir C:\MassPOS-licensing --machine ABCD-EFGH-JKMN-PQRS
 *        --customer-id C001 --customer "Sharma General Store" --valid-until 2027-09-30
 *        [--grace-days 15] [--warn-days 15]
 *   java tools/license/LicenseTool.java list    --dir C:\MassPOS-licensing [--expiring-within 30]
 *   java tools/license/LicenseTool.java inspect --dir C:\MassPOS-licensing (key or .lic file)
 * </pre>
 *
 * <p>A licence key is {@code MPL1.<payload>.<signature>}: the payload is JSON, both parts are base64url
 * without padding, and the Ed25519 signature covers the text {@code MPL1.<payload>}. The app reads it
 * in {@code com.masspos.license.LicenseToken}; change both together.
 */
public class LicenseTool {

    static final String PREFIX = "MPL1";
    static final String PRIVATE_KEY_FILE = "vendor-private.key";
    static final String PUBLIC_KEY_FILE = "vendor-public.key";
    static final String REGISTER_FILE = "licences.csv";
    static final String ISSUED_DIR = "issued";
    static final String PLAN = "SUBSCRIPTION";
    static final int DEFAULT_GRACE_DAYS = 15;
    static final int DEFAULT_WARN_DAYS = 15;
    static final int MAX_DAYS = 365;

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Crockford base32, grouped in fours: what the till's licence screen shows as its machine ID. */
    private static final Pattern MACHINE_ID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}");
    private static final Pattern CUSTOMER_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final List<String> REGISTER_COLUMNS = List.of(
            "issuedOn", "licenseId", "customerId", "customer", "machineId", "plan", "validUntil", "graceDays", "warnDays");

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        try {
            Options options = Options.parse(args);
            switch (args[0]) {
                case "keygen" -> keygen(options);
                case "issue" -> issue(options);
                case "list" -> list(options);
                case "inspect" -> inspect(options);
                default -> {
                    usage();
                    System.exit(2);
                }
            }
        } catch (ToolException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("""
                Mass POS licence tool
                  keygen  --dir <folder>
                  issue   --dir <folder> --machine <ID> --customer-id <id> --customer <name> --valid-until <yyyy-mm-dd>
                          [--grace-days 15] [--warn-days 15]
                  list    --dir <folder> [--expiring-within <days>]
                  inspect --dir <folder> <licence key or .lic file>""");
    }

    // ---- keygen ------------------------------------------------------------------------------------

    static void keygen(Options options) {
        Path dir = options.dir();
        Path privateFile = dir.resolve(PRIVATE_KEY_FILE);
        Path publicFile = dir.resolve(PUBLIC_KEY_FILE);
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            throw new ToolException("%s already holds a key pair. Replacing it would make every licence issued so far "
                    + "unrenewable on installed tills; delete it by hand only if that is what you want".formatted(dir));
        }
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.createDirectories(dir);
            Files.writeString(privateFile, base64(pair.getPrivate().getEncoded()) + System.lineSeparator());
            Files.writeString(publicFile, base64(pair.getPublic().getEncoded()) + System.lineSeparator());
            System.out.println("""
                    Key pair created in %s

                    1. Back up %s somewhere safe and offline. Anyone holding it can issue licences;
                       if it is lost, installed tills can only be renewed after shipping a new build.
                    2. Put this public key into VendorKey.PUBLIC_KEY
                       (src/main/java/com/masspos/license/VendorKey.java) and rebuild the app:

                    %s""".formatted(dir.toAbsolutePath(), PRIVATE_KEY_FILE, base64(pair.getPublic().getEncoded())));
        } catch (GeneralSecurityException | IOException e) {
            throw new ToolException("could not create the key pair: " + e.getMessage());
        }
    }

    // ---- issue -------------------------------------------------------------------------------------

    static void issue(Options options) {
        Path dir = options.dir();
        String machineId = options.required("machine").toUpperCase().replace(" ", "");
        if (!MACHINE_ID.matcher(machineId).matches()) {
            throw new ToolException("--machine must be the ID shown on the till's Licence screen, like ABCD-EFGH-JKMN-PQRS");
        }
        String customerId = options.required("customer-id");
        if (!CUSTOMER_ID.matcher(customerId).matches()) {
            throw new ToolException("--customer-id may use only letters, digits, - and _ (at most 40)");
        }
        String customer = options.required("customer").strip();
        if (customer.isEmpty() || customer.length() > 120 || customer.chars().anyMatch(Character::isISOControl)) {
            throw new ToolException("--customer must be the shop's name, at most 120 characters");
        }
        LocalDate today = LocalDate.now(INDIA);
        LocalDate validUntil = options.date("valid-until");
        if (validUntil.isBefore(today)) {
            throw new ToolException("--valid-until %s is already past".formatted(validUntil));
        }
        int graceDays = options.days("grace-days", DEFAULT_GRACE_DAYS);
        int warnDays = options.days("warn-days", DEFAULT_WARN_DAYS);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("licenseId", UUID.randomUUID().toString());
        payload.put("customerId", customerId);
        payload.put("customer", customer);
        payload.put("machineId", machineId);
        payload.put("plan", PLAN);
        payload.put("issuedOn", today.toString());
        payload.put("validUntil", validUntil.toString());
        payload.put("graceDays", graceDays);
        payload.put("warnDays", warnDays);

        String token = sign(json(payload), readPrivateKey(dir));
        try {
            Path issued = Files.createDirectories(dir.resolve(ISSUED_DIR))
                    .resolve("%s_%s_%s.lic".formatted(customerId, machineId, validUntil));
            Files.writeString(issued, token + System.lineSeparator());
            appendToRegister(dir, payload);
            System.out.printf("""
                    Licence for %s (%s)
                      machine      %s
                      valid until  %s, billing stops after %s (%d grace days)
                      saved to     %s

                    Send this key to the shop; they paste it under Menu > Licence:

                    %s
                    """, customer, customerId, machineId, validUntil, validUntil.plusDays(graceDays), graceDays,
                    issued.toAbsolutePath(), token);
        } catch (IOException e) {
            throw new ToolException("the licence was signed but could not be saved: " + e.getMessage());
        }
    }

    static String sign(String payloadJson, PrivateKey key) {
        String signed = PREFIX + "." + base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new ToolException("could not sign the licence: " + e.getMessage());
        }
    }

    // ---- list --------------------------------------------------------------------------------------

    /** The newest licence for each machine, soonest to lapse first. */
    static void list(Options options) {
        Path register = options.dir().resolve(REGISTER_FILE);
        if (Files.notExists(register)) {
            System.out.println("No licences issued yet.");
            return;
        }
        Integer within = options.has("expiring-within") ? options.days("expiring-within", 0) : null;
        LocalDate today = LocalDate.now(INDIA);
        Map<String, Map<String, String>> latest = new HashMap<>();
        for (Map<String, String> row : readRegister(register)) {
            latest.merge(row.get("machineId"), row,
                    (kept, next) -> next.get("validUntil").compareTo(kept.get("validUntil")) >= 0 ? next : kept);
        }
        List<Map<String, String>> rows = new ArrayList<>(latest.values());
        rows.sort(Comparator.comparing((Map<String, String> row) -> row.get("validUntil")));

        System.out.printf("%-28s %-12s %-19s %-11s %-11s %s%n",
                "CUSTOMER", "CUSTOMER ID", "MACHINE", "VALID TILL", "BILLS TILL", "STATUS");
        int shown = 0;
        for (Map<String, String> row : rows) {
            LocalDate validUntil = LocalDate.parse(row.get("validUntil"));
            LocalDate graceEnds = validUntil.plusDays(Integer.parseInt(row.get("graceDays")));
            long daysLeft = ChronoUnit.DAYS.between(today, validUntil);
            if (within != null && daysLeft > within) {
                continue;
            }
            String status = daysLeft >= 0 ? daysLeft + " days left"
                    : !today.isAfter(graceEnds) ? "IN GRACE, locks after " + graceEnds
                    : "LOCKED since " + graceEnds.plusDays(1);
            System.out.printf("%-28s %-12s %-19s %-11s %-11s %s%n", clip(row.get("customer"), 28),
                    clip(row.get("customerId"), 12), row.get("machineId"), validUntil, graceEnds, status);
            shown++;
        }
        if (shown == 0) {
            System.out.println("(none)");
        }
    }

    // ---- inspect -----------------------------------------------------------------------------------

    static void inspect(Options options) {
        if (options.positional().isEmpty()) {
            throw new ToolException("give a licence key, or the path of a .lic file");
        }
        String input = options.positional().get(0);
        String token;
        try {
            Path file = Path.of(input);
            token = Files.isRegularFile(file) ? Files.readString(file) : input;
        } catch (IOException | RuntimeException e) {
            token = input;
        }
        token = token.replaceAll("\\s+", "");
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new ToolException("that is not a Mass POS licence key");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ToolException("the licence key is damaged");
        }
        System.out.println(payload);
        Path publicFile = options.dir().resolve(PUBLIC_KEY_FILE);
        if (Files.notExists(publicFile)) {
            System.out.println("(signature not checked: no " + PUBLIC_KEY_FILE + " in " + options.dir() + ")");
            return;
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(publicFile).strip())));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            boolean valid = signature.verify(Base64.getUrlDecoder().decode(parts[2]));
            System.out.println(valid ? "Signature: valid, issued with this key pair"
                    : "Signature: NOT VALID for this key pair");
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            System.out.println("Signature: could not be checked (" + e.getMessage() + ")");
        }
    }

    // ---- register ----------------------------------------------------------------------------------

    private static void appendToRegister(Path dir, Map<String, Object> payload) throws IOException {
        Path register = dir.resolve(REGISTER_FILE);
        StringBuilder text = new StringBuilder();
        if (Files.notExists(register)) {
            text.append(String.join(",", REGISTER_COLUMNS)).append(System.lineSeparator());
        }
        List<String> cells = new ArrayList<>();
        for (String column : REGISTER_COLUMNS) {
            cells.add(csv(String.valueOf(payload.get(column))));
        }
        text.append(String.join(",", cells)).append(System.lineSeparator());
        Files.writeString(register, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<Map<String, String>> readRegister(Path register) {
        List<String> lines;
        try {
            lines = Files.readAllLines(register);
        } catch (IOException e) {
            throw new ToolException("could not read " + register + ": " + e.getMessage());
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (cells.size() != REGISTER_COLUMNS.size()) {
                throw new ToolException("%s has a malformed line: %s".formatted(register, line));
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                row.put(REGISTER_COLUMNS.get(i), cells.get(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static PrivateKey readPrivateKey(Path dir) {
        Path file = dir.resolve(PRIVATE_KEY_FILE);
        if (Files.notExists(file)) {
            throw new ToolException("no %s in %s; run keygen first".formatted(PRIVATE_KEY_FILE, dir));
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(file).strip())));
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new ToolException("could not read %s: %s".formatted(file, e.getMessage()));
        }
    }

    /** Flat JSON object of strings and numbers, the only shape a licence payload has. */
    static String json(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append(quote(field.getKey())).append(':');
            out.append(field.getValue() instanceof Number ? field.getValue().toString() : quote(field.getValue().toString()));
        }
        return out.append('}').toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String clip(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "…";
    }

    record Options(Map<String, String> named, List<String> positional) {

        static Options parse(String[] args) {
            Map<String, String> named = new HashMap<>();
            List<String> positional = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    if (i + 1 >= args.length) {
                        throw new ToolException(args[i] + " needs a value");
                    }
                    named.put(args[i].substring(2), args[++i]);
                } else {
                    positional.add(args[i]);
                }
            }
            return new Options(named, positional);
        }

        boolean has(String name) {
            return named.containsKey(name);
        }

        String required(String name) {
            String value = named.get(name);
            if (value == null || value.isBlank()) {
                throw new ToolException("--" + name + " is required");
            }
            return value;
        }

        Path dir() {
            return Path.of(required("dir"));
        }

        LocalDate date(String name) {
            try {
                return LocalDate.parse(required(name));
            } catch (DateTimeParseException e) {
                throw new ToolException("--" + name + " must be a date like 2027-09-30");
            }
        }

        int days(String name, int fallback) {
            if (!has(name)) {
                return fallback;
            }
            try {
                int days = Integer.parseInt(named.get(name));
                if (days < 0 || days > MAX_DAYS) {
                    throw new NumberFormatException();
                }
                return days;
            } catch (NumberFormatException e) {
                throw new ToolException("--%s must be a whole number of days from 0 to %d".formatted(name, MAX_DAYS));
            }
        }
    }

    static class ToolException extends RuntimeException {
        ToolException(String message) {
            super(message);
        }
    }
}
