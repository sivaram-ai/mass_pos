package com.masspos.counter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.hardware.EscPos;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads the bill the server sent. Kept apart from {@link PrintAgent} so the parsing can be tested
 * without opening a port, and so the shape the two ends agree on is written down in one place.
 */
final class PrintJobs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PrintJobs() {
    }

    /**
     * The shop as the screen knows it, for a test page. Empty for an empty or unreadable body: a
     * test page is worth printing with a heading missing, and the screen may simply not have the
     * name yet.
     */
    static String shopName(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            return JSON.readTree(body).path("shop").asText("");
        } catch (Exception notJson) {
            return "";
        }
    }

    /** Where a counter already running on this machine is billing, read back out of its /hello. */
    static String serverOf(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            return JSON.readTree(body).path("server").asText("");
        } catch (Exception notJson) {
            return "";
        }
    }

    /**
     * Why the sign-in screen is sending this counter back to its settings, in the server's own
     * words, so the window can say what went wrong instead of opening blank.
     */
    static String reason(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            return JSON.readTree(body).path("reason").asText("");
        } catch (Exception notJson) {
            return "";
        }
    }

    /**
     * @throws RuntimeException for anything that is not a bill, which the agent turns into a 400
     *                          rather than letting a stray POST reach the printer
     */
    static PrintAgent.Job read(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            byte[] bytes = Base64.getDecoder().decode(root.path("escpos").asText(""));
            List<EscPos.Line> lines = new ArrayList<>();
            for (JsonNode line : root.path("lines")) {
                lines.add(new EscPos.Line(
                        line.path("text").asText(""),
                        EscPos.Align.valueOf(line.path("align").asText("LEFT")),
                        line.path("bold").asBoolean(false),
                        line.path("doubleWidth").asBoolean(false),
                        line.path("doubleHeight").asBoolean(false),
                        line.hasNonNull("qr") ? line.get("qr").asText() : null,
                        line.path("parcelMark").asBoolean(false)));
            }
            if (bytes.length == 0 && lines.isEmpty()) {
                throw new IllegalArgumentException("a bill with nothing on it");
            }
            return new PrintAgent.Job(bytes, List.copyOf(lines));
        } catch (RuntimeException alreadyClear) {
            throw alreadyClear;
        } catch (Exception notJson) {
            throw new IllegalArgumentException("not a bill", notJson);
        }
    }
}
