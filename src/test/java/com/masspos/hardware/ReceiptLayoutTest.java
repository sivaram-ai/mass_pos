package com.masspos.hardware;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReceiptLayoutTest {

    /** A4 less the 1 cm margins a driver printer leaves, in points. */
    private static final double WIDTH = 538;
    private static final double HEIGHT = 785;
    private static final int COLUMNS = 48;

    @Test
    void sizesTheFontSoThatTheConfiguredColumnsFillTheWidth() {
        ReceiptLayout layout = layout(text("Item"));

        assertThat(layout.charWidth() * COLUMNS).isCloseTo(WIDTH, within(0.5));
    }

    @Test
    void centresAndRightAlignsWithinThoseColumns() {
        ReceiptLayout layout = layout(text("Item"));
        double column = layout.charWidth();

        assertThat(layout.x(line("0123456789", EscPos.Align.LEFT, false))).isCloseTo(0, within(0.01));
        assertThat(layout.x(line("0123456789", EscPos.Align.CENTER, false))).isCloseTo(19 * column, within(0.01));
        assertThat(layout.x(line("0123456789", EscPos.Align.RIGHT, false))).isCloseTo(38 * column, within(0.01));
    }

    @Test
    void doubleWidthTextIsCentredOverTwoColumnsPerCharacter() {
        ReceiptLayout layout = layout(text("Item"));

        assertThat(layout.x(line("0123456789", EscPos.Align.CENTER, true)))
                .isCloseTo(14 * layout.charWidth(), within(0.01));
    }

    @Test
    void aShortBillIsOnePage() {
        assertThat(layout(lines(20)).pages()).isEqualTo(1);
    }

    @Test
    void aLongBillRunsOntoFurtherPages() {
        ReceiptLayout layout = layout(lines(400));

        assertThat(layout.pages()).isGreaterThan(1);
        assertThat(layout.pages()).isLessThan(20);
    }

    @Test
    void everyLineLandsOnExactlyOnePage() {
        ReceiptLayout layout = layout(lines(400));

        assertThat(IntStream.range(0, layout.pages()).map(layout::lineCount).sum()).isEqualTo(400);
    }

    @Test
    void aDoubleHeightLineTakesTwoLinesOfRoom() {
        ReceiptLayout layout = layout(text("TOTAL"));

        assertThat(layout.height(new EscPos.Line("TOTAL", EscPos.Align.LEFT, true, false, true, null)))
                .isCloseTo(2 * layout.lineHeight(), within(0.01));
    }

    @Test
    void aQrCodeTakesASquareBlockOfItsOwn() {
        ReceiptLayout layout = layout(text("Scan"));
        EscPos.Line qr = new EscPos.Line("", EscPos.Align.CENTER, false, false, false, "upi://pay?pa=shop@okbank");

        assertThat(layout.height(qr)).isGreaterThan(4 * layout.lineHeight());
    }

    @Test
    void paintsInkOnThePage() {
        ReceiptLayout layout = layout(List.of(
                new EscPos.Line("THALAPATHI", EscPos.Align.CENTER, true, true, true, null),
                text("Item                                      Amount"),
                new EscPos.Line("", EscPos.Align.CENTER, false, false, false, "upi://pay?pa=shop@okbank")));

        assertThat(darkPixels(layout, 0)).isGreaterThan(500);
        assertThat(layout.pages()).isEqualTo(1);
    }

    @Test
    void paintsEveryPageOfALongBill() {
        ReceiptLayout layout = layout(lines(400));

        for (int page = 0; page < layout.pages(); page++) {
            assertThat(darkPixels(layout, page)).as("page %d", page).isGreaterThan(100);
        }
    }

    /**
     * The characters have to end where the paper does. They did not: the font was sized from
     * unrounded advances while the page was drawn with rounded ones, so every line ran over.
     */
    @Test
    void aLineOfFullWidthEndsWithinThePaper() {
        ReceiptLayout layout = layout(text("0".repeat(COLUMNS)));

        int right = rightmostInk(layout);

        assertThat(right).isLessThanOrEqualTo((int) Math.ceil(WIDTH));
        assertThat(right).isGreaterThan((int) (WIDTH - 2 * layout.charWidth()));
    }

    private static int rightmostInk(ReceiptLayout layout) {
        BufferedImage image = render(layout, 0, (int) WIDTH * 2);
        for (int x = image.getWidth() - 1; x >= 0; x--) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) & 0xFF) < 128) {
                    return x + 1;
                }
            }
        }
        return 0;
    }

    private static int darkPixels(ReceiptLayout layout, int page) {
        BufferedImage image = render(layout, page, (int) WIDTH);
        int dark = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFF) < 128) {
                    dark++;
                }
            }
        }
        return dark;
    }

    /** Wider than the paper when asked, so that anything running off the edge is still drawn. */
    private static BufferedImage render(ReceiptLayout layout, int page, int imageWidth) {
        BufferedImage image = new BufferedImage(imageWidth, (int) HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        layout.paint(g, page);
        g.dispose();
        return image;
    }

    private static ReceiptLayout layout(EscPos.Line single) {
        return layout(List.of(single));
    }

    private static ReceiptLayout layout(List<EscPos.Line> lines) {
        return new ReceiptLayout(lines, COLUMNS, WIDTH, HEIGHT);
    }

    private static List<EscPos.Line> lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> text(i + ". Item")).toList();
    }

    private static EscPos.Line text(String text) {
        return line(text, EscPos.Align.LEFT, false);
    }

    private static EscPos.Line line(String text, EscPos.Align align, boolean doubleWidth) {
        return new EscPos.Line(text, align, false, doubleWidth, false, null);
    }
}
