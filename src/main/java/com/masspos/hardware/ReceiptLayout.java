package com.masspos.hardware;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lays a receipt out for a printer that renders pages instead of executing ESC/POS: picks a
 * monospaced font in which the job's columns exactly span the paper, stacks the lines with their
 * styling, draws the QR codes a thermal printer would have produced itself, and breaks the result
 * into pages. All sizes are in points, measured from the top left of the printable area.
 */
class ReceiptLayout {

    private static final Logger log = LoggerFactory.getLogger(ReceiptLayout.class);

    /** Size the font is measured at; everything scales from it, so the value itself is arbitrary. */
    private static final float REFERENCE_SIZE = 100f;
    /** Printing, so hinting and fractional metrics both on. */
    private static final FontRenderContext METRICS = new FontRenderContext(null, true, true);
    /** Columns a QR code spans. A third of an 80 mm roll, which scans comfortably. */
    private static final int QR_COLUMNS = 16;
    /** White space above and below a QR code, as a fraction of a line. */
    private static final double QR_GAP = 0.3;
    /** The parcel mark, and the space before it, against the code they sit beside. */
    private static final double PARCEL_MARK = 0.52;
    private static final double PARCEL_GAP = 0.10;
    /** Model 2, error correction M: the same symbol the ESC/POS printer would have drawn. */
    private static final Map<EncodeHintType, Object> QR_HINTS = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.MARGIN, 2);

    private final List<EscPos.Line> lines;
    private final int columns;
    private final double width;
    private final double height;
    private final Font font;
    private final double charWidth;
    private final double lineHeight;
    private final double ascent;
    private final List<Integer> pageStarts;

    ReceiptLayout(List<EscPos.Line> lines, int columns, double width, double height) {
        this.lines = List.copyOf(lines);
        this.columns = columns;
        this.width = width;
        this.height = height;
        Font reference = new Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(REFERENCE_SIZE);
        Font fitted = reference.deriveFont((float) (REFERENCE_SIZE * width / (columns * advance(reference))));
        // Hinting keeps a font from scaling quite linearly, so measure again at the size we arrived at.
        this.font = fitted.deriveFont((float) (fitted.getSize2D() * width / (columns * advance(fitted))));
        this.charWidth = width / columns;
        LineMetrics lineMetrics = font.getLineMetrics("0", METRICS);
        this.lineHeight = lineMetrics.getHeight();
        this.ascent = lineMetrics.getAscent();
        this.pageStarts = paginate();
    }

    int pages() {
        return lines.isEmpty() ? 0 : pageStarts.size();
    }

    int lineCount(int page) {
        return end(page) - pageStarts.get(page);
    }

    double charWidth() {
        return charWidth;
    }

    double lineHeight() {
        return lineHeight;
    }

    /** Vertical room one line needs, which a QR code or double-height text makes more than one. */
    double height(EscPos.Line line) {
        double text = lineHeight * (line.doubleHeight() ? 2 : 1);
        if (line.qr() == null) {
            return text;
        }
        double qr = qrSide() + 2 * QR_GAP * lineHeight;
        return line.text().isEmpty() ? qr : text + qr;
    }

    /** Where the line starts, from the alignment it was printed with. */
    double x(EscPos.Line line) {
        double free = Math.max(0, columns - line.columns()) * charWidth;
        return switch (line.align()) {
            case LEFT -> 0;
            case CENTER -> free / 2;
            case RIGHT -> free;
        };
    }

    void paint(Graphics2D g, int page) {
        if (page < 0 || page >= pages()) {
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Without this the printer rounds every character's advance up and the line overruns the paper.
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        double y = 0;
        for (int i = pageStarts.get(page); i < end(page); i++) {
            EscPos.Line line = lines.get(i);
            double textHeight = line.text().isEmpty() ? 0 : lineHeight * (line.doubleHeight() ? 2 : 1);
            if (!line.text().isEmpty()) {
                drawText(g, line, y);
            }
            if (line.qr() != null) {
                drawQr(g, line, y + textHeight + QR_GAP * lineHeight);
            }
            y += height(line);
        }
    }

    private void drawText(Graphics2D g, EscPos.Line line, double y) {
        Font styled = line.bold() ? font.deriveFont(Font.BOLD) : font;
        if (line.doubleWidth() || line.doubleHeight()) {
            styled = styled.deriveFont(AffineTransform.getScaleInstance(
                    line.doubleWidth() ? 2 : 1, line.doubleHeight() ? 2 : 1));
        }
        g.setFont(styled);
        g.drawString(line.text(), (float) x(line), (float) (y + ascent * (line.doubleHeight() ? 2 : 1)));
    }

    /** A failed QR must not cost the shop its bill, so the rest of the receipt still prints. */
    private void drawQr(Graphics2D g, EscPos.Line line, double top) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(line.qr(), BarcodeFormat.QR_CODE, 1, 1, QR_HINTS);
        } catch (WriterException e) {
            log.warn("Receipt printed without its QR code: {}", e.getMessage());
            return;
        }
        double side = qrSide();
        double mark = line.parcelMark() ? side * PARCEL_MARK : 0;
        double gap = line.parcelMark() ? side * PARCEL_GAP : 0;
        // The code and its mark are placed as one block, so the mark cannot fall off the paper.
        double block = side + gap + mark;
        double module = side / matrix.getWidth();
        double left = switch (line.align()) {
            case LEFT -> 0;
            case CENTER -> (width - block) / 2;
            case RIGHT -> width - block;
        };
        for (int row = 0; row < matrix.getHeight(); row++) {
            for (int column = 0; column < matrix.getWidth(); column++) {
                if (matrix.get(column, row)) {
                    g.fill(new Rectangle2D.Double(left + column * module, top + row * module, module, module));
                }
            }
        }
        if (line.parcelMark()) {
            ParcelQr.drawMark(g, left + side + gap, top + (side - mark) / 2, mark);
        }
    }

    private double qrSide() {
        return Math.min(QR_COLUMNS * charWidth, width);
    }

    private static double advance(Font font) {
        return font.getStringBounds("0", METRICS).getWidth();
    }

    /**
     * A line never straddles a page break. One taller than the whole page - a QR code on very small
     * paper - stays where it is and runs over rather than looping forever.
     */
    private List<Integer> paginate() {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        double used = 0;
        for (int i = 0; i < lines.size(); i++) {
            double room = height(lines.get(i));
            if (used > 0 && used + room > height) {
                starts.add(i);
                used = 0;
            }
            used += room;
        }
        return List.copyOf(starts);
    }

    private int end(int page) {
        return page + 1 < pageStarts.size() ? pageStarts.get(page + 1) : lines.size();
    }
}
