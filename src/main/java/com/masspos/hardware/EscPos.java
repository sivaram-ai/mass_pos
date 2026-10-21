package com.masspos.hardware;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One ESC/POS print job, in the Epson command set that practically every thermal receipt printer
 * emulates. Alongside the bytes it keeps a plain-text preview of what gets printed, used by the
 * FILE printer and by tests.
 *
 * <p>Not thread-safe: build a document on one thread, then hand it to {@link ThermalPrinter}.
 */
public final class EscPos {

    public enum Align { LEFT, CENTER, RIGHT }

    /** Pin of the printer's RJ11/RJ12 drawer-kick ("DK") port that the drawer is wired to. */
    public enum DrawerPin { PIN_2, PIN_5 }

    /**
     * One line as it will appear on paper, kept for printers that render a job instead of executing
     * it ({@link WindowsPrinterConnection}). The text is unaligned and unpadded: a renderer places
     * it itself, because it knows the real page width.
     *
     * @param qr payload of a QR code that ends this line, or null
     */
    public record Line(String text, Align align, boolean bold, boolean doubleWidth, boolean doubleHeight,
                       String qr, boolean parcelMark) {

        public Line(String text, Align align, boolean bold, boolean doubleWidth, boolean doubleHeight, String qr) {
            this(text, align, bold, doubleWidth, doubleHeight, qr, false);
        }

        /** Columns taken up, counting each double-width character twice. */
        public int columns() {
            return doubleWidth ? text.length() * 2 : text.length();
        }

        public boolean isEmpty() {
            return text.isBlank() && qr == null;
        }
    }

    private static final int ESC = 0x1B;
    private static final int GS = 0x1D;
    private static final int DLE = 0x10;
    private static final int LF = 0x0A;
    private static final int MAX_QR_BYTES = 7089;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
    private final StringBuilder preview = new StringBuilder();
    private final StringBuilder previewLine = new StringBuilder();
    private final List<Line> printedLines = new ArrayList<>();
    private final CharsetEncoder encoder;
    private final int columns;
    private Align align = Align.LEFT;
    private boolean bold;
    private boolean doubleWidth;
    private boolean doubleHeight;
    private String previewQr;
    private boolean previewParcelMark;

    /** Starts a job: resets the printer ({@code ESC @}) and selects the code page ({@code ESC t n}). */
    public EscPos(CodePage codePage, int columns) {
        this.encoder = codePage.charset().newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(new byte[] {'?'});
        this.columns = columns;
        raw(ESC, '@');
        raw(ESC, 't', codePage.escPosTable());
    }

    /** DLE EOT n real-time status request: 1 printer, 2 offline cause, 3 error cause, 4 paper sensor. */
    public static byte[] statusRequest(int n) {
        return new byte[] {DLE, 0x04, (byte) n};
    }

    /**
     * Maps ₹ (absent from every text-mode code page) to "Rs." and blanks control characters, so data
     * such as a product name can never smuggle printer commands (e.g. a drawer kick) into a job.
     */
    static String sanitize(String text) {
        return text.replace("₹", "Rs.").replaceAll("\\p{Cntrl}", " ");
    }

    /** Breaks text to fit a width, for a caller laying out its own columns. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            while (word.length() > width) {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (!current.isEmpty() && current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (!word.isEmpty()) {
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(word);
            }
        }
        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    public int columns() {
        return columns;
    }

    /** Characters per line at the current text width. */
    public int lineWidth() {
        return doubleWidth ? columns / 2 : columns;
    }

    public EscPos align(Align align) {
        this.align = align;
        raw(ESC, 'a', align.ordinal());
        return this;
    }

    public EscPos bold(boolean on) {
        this.bold = on;
        raw(ESC, 'E', on ? 1 : 0);
        return this;
    }

    public EscPos size(boolean doubleWidth, boolean doubleHeight) {
        this.doubleWidth = doubleWidth;
        this.doubleHeight = doubleHeight;
        raw(GS, '!', (doubleWidth ? 0x10 : 0) | (doubleHeight ? 0x01 : 0));
        return this;
    }

    /** Characters the code page cannot represent print as '?'. */
    public EscPos text(String text) {
        String printable = sanitize(text);
        try {
            ByteBuffer encoded = encoder.reset().encode(CharBuffer.wrap(printable));
            out.write(encoded.array(), encoded.arrayOffset() + encoded.position(), encoded.remaining());
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Unreachable with REPLACE coding actions", e);
        }
        previewLine.append(printable);
        return this;
    }

    public EscPos line(String text) {
        return text(text).newLine();
    }

    public EscPos newLine() {
        raw(LF);
        flushPreviewLine();
        return this;
    }

    /** Word-wraps to the current line width. */
    public EscPos wrapped(String text) {
        wrap(sanitize(text), lineWidth()).forEach(this::line);
        return this;
    }

    /** Left text and right-aligned text on one line; if they do not fit, the right part gets its own line. */
    public EscPos leftRight(String left, String right) {
        String l = sanitize(left);
        String r = sanitize(right);
        int width = lineWidth();
        if (l.length() + 1 + r.length() <= width) {
            return line(l + " ".repeat(width - l.length() - r.length()) + r);
        }
        wrap(l, width).forEach(this::line);
        return line(" ".repeat(Math.max(0, width - r.length())) + r);
    }

    public EscPos separator(char c) {
        return line(String.valueOf(c).repeat(lineWidth()));
    }

    public EscPos feed(int lines) {
        raw(ESC, 'd', lines);
        if (hasPendingLine()) {
            flushPreviewLine();
        }
        preview.append("\n".repeat(lines));
        for (int i = 0; i < lines; i++) {
            printedLines.add(new Line("", align, bold, doubleWidth, doubleHeight, null));
        }
        return this;
    }

    /** QR code (model 2, error correction M) printed at the current alignment. */
    public EscPos qrCode(String data, int moduleSize) {
        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_QR_BYTES) {
            throw new IllegalArgumentException("QR payload too large: " + payload.length + " bytes");
        }
        if (moduleSize < 1 || moduleSize > 16) {
            throw new IllegalArgumentException("QR module size must be 1-16 dots, got " + moduleSize);
        }
        int storeLength = payload.length + 3;
        raw(GS, '(', 'k', 4, 0, '1', 'A', '2', 0);                            // model 2
        raw(GS, '(', 'k', 3, 0, '1', 'C', moduleSize);                        // module size
        raw(GS, '(', 'k', 3, 0, '1', 'E', '1');                               // error correction M
        raw(GS, '(', 'k', storeLength & 0xFF, storeLength >> 8, '1', 'P', '0'); // store data
        out.writeBytes(payload);
        raw(GS, '(', 'k', 3, 0, '1', 'Q', '0');                               // print symbol
        previewQr = data;
        return newLine();
    }

    /**
     * A picture, dot for dot ({@code GS v 0}). The till only reaches for this where the printer's
     * own commands cannot do the job - a QR code with something printed beside it - because it
     * sends many times the bytes a command would.
     *
     * @param dots    black dots as {@code [row][column]}; every row must be the same length
     * @param preview what the text preview should show in its place
     */
    public EscPos raster(boolean[][] dots, String preview, boolean parcelMark) {
        int height = dots.length;
        int width = height == 0 ? 0 : dots[0].length;
        if (width == 0) {
            return this;
        }
        int bytesAcross = (width + 7) / 8;
        raw(GS, 'v', '0', 0, bytesAcross & 0xFF, bytesAcross >> 8, height & 0xFF, height >> 8);
        for (boolean[] row : dots) {
            for (int group = 0; group < bytesAcross; group++) {
                int bits = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = group * 8 + bit;
                    if (x < width && row[x]) {
                        bits |= 0x80 >> bit;
                    }
                }
                out.write(bits);
            }
        }
        previewQr = preview;
        previewParcelMark = parcelMark;
        return newLine();
    }

    /** Feeds to the cutter and makes a partial cut ({@code GS V 66 n}). */
    public EscPos cut() {
        if (hasPendingLine()) {
            newLine();
        }
        raw(GS, 'V', 66, 3);
        return this;
    }

    /** Drawer kick ({@code ESC p m t1 t2}); pulse times go in 2 ms units, so the maximum is 510 ms. */
    public EscPos openDrawer(DrawerPin pin, Duration pulse) {
        int on = (int) Math.min(255, Math.max(1, pulse.toMillis() / 2));
        raw(ESC, 'p', pin == DrawerPin.PIN_2 ? 0 : 1, on, Math.max(on, 250));
        return this;
    }

    public byte[] toBytes() {
        return out.toByteArray();
    }

    public String plainText() {
        return hasPendingLine() ? preview + renderedPendingLine() : preview.toString();
    }

    /**
     * Every line this job prints, in order, with the styling in force when each was ended - what a
     * printer that renders text needs, where {@link #toBytes()} suits one that executes commands.
     * A line still being built counts, as {@link #plainText()} shows it too.
     */
    public List<Line> printedLines() {
        if (!hasPendingLine()) {
            return List.copyOf(printedLines);
        }
        List<Line> all = new ArrayList<>(printedLines);
        all.add(pendingLine());
        return List.copyOf(all);
    }

    private void flushPreviewLine() {
        String rendered = renderedPendingLine();
        int pad = Math.max(0, lineWidth() - rendered.length());
        String line = switch (align) {
            case LEFT -> rendered;
            case CENTER -> " ".repeat(pad / 2) + rendered;
            case RIGHT -> " ".repeat(pad) + rendered;
        };
        preview.append(line.stripTrailing()).append('\n');
        printedLines.add(pendingLine());
        previewLine.setLength(0);
        previewQr = null;
    }

    private Line pendingLine() {
        return new Line(previewLine.toString(), align, bold, doubleWidth, doubleHeight, previewQr, previewParcelMark);
    }

    /** The pending line as text, with a QR code standing in as {@code [QR payload]}. */
    private String renderedPendingLine() {
        if (previewQr == null) {
            return previewLine.toString();
        }
        return previewLine + "[QR " + previewQr + "]" + (previewParcelMark ? " (P)" : "");
    }

    private boolean hasPendingLine() {
        return !previewLine.isEmpty() || previewQr != null;
    }

    private void raw(int... codes) {
        for (int code : codes) {
            out.write(code);
        }
    }
}
