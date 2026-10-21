package com.masspos.hardware;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * The UPI code with a circled P beside it, the way a counter marks a parcel bill by hand.
 *
 * <p>A receipt printer draws a QR code itself, from a command, and then carries on at the next
 * line - there is no way to set type alongside it. So for a parcel the till draws the whole block,
 * code and mark together, and sends it as a picture.
 */
public final class ParcelQr {

    /** Model 2, error correction M: the same symbol the ESC/POS command would have produced. */
    private static final Map<EncodeHintType, Object> HINTS = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.MARGIN, 2);

    /** How wide the mark is, against the code beside it, and the space between them. */
    private static final double MARK = 0.70;
    private static final double GAP = 0.08;
    /**
     * How much of the paper the code aims for; the rest is the margin the mark sits in. Dots per
     * module are whole numbers, so this is a target, not a width: 42% keeps the usual bill's code
     * at five dots a module, which is the size it printed at before there was a mark at all.
     */
    private static final int CODE_SHARE_PERCENT = 42;

    private ParcelQr() {
    }

    /**
     * Black dots for the whole block, as {@code [row][column]}.
     *
     * <p>The block is padded on the left by as much as the mark takes on the right, so that
     * printing it centred leaves <em>the code</em> centred on the paper - in the same place,
     * at the same size, whether the bill is a parcel or not.
     *
     * @param paperDots the printer's full width in dots (a Font A column is 12 of them)
     */
    public static boolean[][] compose(String upi, int paperDots) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(upi, BarcodeFormat.QR_CODE, 1, 1, HINTS);
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot make a QR code from " + upi, e);
        }
        int modules = matrix.getWidth();
        // A whole number of dots per module, or the code comes out ragged and may not scan.
        int scale = Math.max(1, paperDots * CODE_SHARE_PERCENT / 100 / modules);
        int code = modules * scale;
        int gap = Math.max(4, (int) Math.round(code * GAP));
        // Never wider than the margin beside the centred code, or the block would not fit the paper.
        int mark = Math.max(0, Math.min((int) Math.round(code * MARK), (paperDots - code) / 2 - gap));
        int lead = gap + mark;

        BufferedImage block = new BufferedImage(lead + code + gap + mark, code, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = block.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, block.getWidth(), block.getHeight());
        g.setColor(Color.BLACK);
        for (int row = 0; row < modules; row++) {
            for (int column = 0; column < modules; column++) {
                if (matrix.get(column, row)) {
                    g.fillRect(lead + column * scale, row * scale, scale, scale);
                }
            }
        }
        if (mark > 0) {
            drawMark(g, lead + code + gap, (code - mark) / 2.0, mark);
        }
        g.dispose();
        return dotsOf(block);
    }

    /** The mark on its own, for a page the till lays out itself rather than a printer's dots. */
    public static void drawMark(Graphics2D g, double x, double y, double size) {
        Graphics2D mark = (Graphics2D) g.create();
        mark.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        mark.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double line = Math.max(1.5, size / 11);
        mark.setStroke(new BasicStroke((float) line));
        mark.draw(new Ellipse2D.Double(x + line, y + line, size - 2 * line, size - 2 * line));

        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 100)
                .deriveFont((float) (size * 0.62));
        mark.setFont(font);
        String p = "P";
        double width = font.getStringBounds(p, mark.getFontRenderContext()).getWidth();
        // Cap height, not the font's line height: the letter is centred on the circle, not its box.
        double cap = mark.getFontMetrics().getAscent() * 0.72;
        mark.drawString(p, (float) (x + (size - width) / 2), (float) (y + size / 2 + cap / 2));
        mark.dispose();
    }

    private static boolean[][] dotsOf(BufferedImage image) {
        boolean[][] dots = new boolean[image.getHeight()][image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                dots[y][x] = (image.getRGB(x, y) & 0xFF) < 128;
            }
        }
        return dots;
    }
}
