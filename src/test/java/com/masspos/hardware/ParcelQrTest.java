package com.masspos.hardware;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParcelQrTest {

    private static final String UPI =
            "upi://pay?pa=shop@okicici&pn=THALAPATHY%20BIRIYANI&am=140.00&cu=INR&tn=T1-2627-00129";

    /** Drawing the code ourselves is only worth anything if a phone can still read it. */
    @Test
    void theCodeStillScans() throws Exception {
        boolean[][] dots = ParcelQr.compose(UPI, 260);

        Result read = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new Dots(dots))));

        assertThat(read.getText()).isEqualTo(UPI);
    }

    @Test
    void theMarkSitsToTheRightOfTheCodeAndIsRoundedOnNothing() {
        boolean[][] dots = ParcelQr.compose(UPI, 260);
        int height = dots.length;
        int width = dots[0].length;

        assertThat(width).as("wider than it is tall: the mark is beside the code").isGreaterThan(height);
        // The band to the right of the code carries the mark, and the corners of that band stay clear.
        int markLeft = height + (width - height) / 4;
        assertThat(inkIn(dots, markLeft, width, height / 3, 2 * height / 3)).as("the P and its circle")
                .isGreaterThan(20);
        assertThat(inkIn(dots, width - 3, width, 0, 3)).as("the top right corner, outside the circle")
                .isZero();
    }

    @Test
    void everyRowIsTheSameLength() {
        boolean[][] dots = ParcelQr.compose(UPI, 200);

        assertThat(dots).isNotEmpty();
        for (boolean[] row : dots) {
            assertThat(row).hasSize(dots[0].length);
        }
    }

    private static int inkIn(boolean[][] dots, int fromX, int toX, int fromY, int toY) {
        int ink = 0;
        for (int y = Math.max(0, fromY); y < Math.min(dots.length, toY); y++) {
            for (int x = Math.max(0, fromX); x < Math.min(dots[y].length, toX); x++) {
                if (dots[y][x]) {
                    ink++;
                }
            }
        }
        return ink;
    }

    /** Just enough of a zxing source to read back what we drew. */
    private static final class Dots extends LuminanceSource {

        private final boolean[][] dots;

        private Dots(boolean[][] dots) {
            super(dots[0].length, dots.length);
            this.dots = dots;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            byte[] into = row != null && row.length >= getWidth() ? row : new byte[getWidth()];
            for (int x = 0; x < getWidth(); x++) {
                into[x] = (byte) (dots[y][x] ? 0 : 255);
            }
            return into;
        }

        @Override
        public byte[] getMatrix() {
            byte[] all = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++) {
                getRow(y, null);
                for (int x = 0; x < getWidth(); x++) {
                    all[y * getWidth() + x] = (byte) (dots[y][x] ? 0 : 255);
                }
            }
            return all;
        }
    }

    @Test
    void refusesWhatCannotBeMadeIntoACode() {
        String tooLong = "x".repeat(8000);

        assertThat(catchThrowable(() -> ParcelQr.compose(tooLong, 200)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Throwable catchThrowable(Runnable work) {
        try {
            work.run();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }

    @Test
    void readsBackOnNarrowPaperToo() throws NotFoundException, com.google.zxing.ChecksumException,
            com.google.zxing.FormatException {
        boolean[][] dots = ParcelQr.compose(UPI, 170);

        assertThat(new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new Dots(dots)))).getText())
                .isEqualTo(UPI);
    }
}
