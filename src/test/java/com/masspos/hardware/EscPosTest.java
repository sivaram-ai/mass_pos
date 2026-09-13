package com.masspos.hardware;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

class EscPosTest {

    /** ESC @ (reset) + ESC t n (code page). */
    private static final int PREAMBLE_LENGTH = 5;
    private static final char ESC = (char) 0x1B;
    private static final char NUL = (char) 0x00;

    @Test
    void jobStartsWithResetAndCodePageSelection() {
        assertThat(new EscPos(CodePage.PC437, 48).toBytes()).containsExactly(bytes(0x1B, '@', 0x1B, 't', 0));
        assertThat(new EscPos(CodePage.PC858, 48).toBytes()).containsExactly(bytes(0x1B, '@', 0x1B, 't', 19));
    }

    @Test
    void mapsTheRupeeSignAndNeutralisesControlCharacters() {
        EscPos doc = new EscPos(CodePage.PC437, 48).text("₹10 " + ESC + "p" + NUL + "é अ");

        // ESC in a product name must not reach the printer as a command; é is 0x82 in PC437; अ has no glyph.
        assertThat(body(doc)).containsExactly(concat("Rs.10  p ".getBytes(US_ASCII), bytes(0x82, ' ', '?')));
    }

    @Test
    void leftRightFillsTheLineOrMovesTheAmountDown() {
        EscPos doc = new EscPos(CodePage.PC437, 32)
                .leftRight("Total", "200.00")
                .leftRight("A description far too long to share", "9.99");

        assertThat(doc.plainText().lines()).containsExactly(
                "Total" + " ".repeat(21) + "200.00",
                "A description far too long to",
                "share",
                " ".repeat(28) + "9.99");
    }

    @Test
    void doubleWidthHalvesTheLine() {
        EscPos doc = new EscPos(CodePage.PC437, 48).size(true, true);

        assertThat(doc.lineWidth()).isEqualTo(24);
        assertThat(doc.separator('=').plainText()).isEqualTo("=".repeat(24) + "\n");
    }

    @Test
    void wrapHardSplitsWordsLongerThanTheLine() {
        assertThat(EscPos.wrap("ABCDEFGHIJ KL", 4)).containsExactly("ABCD", "EFGH", "IJ", "KL");
    }

    @Test
    void drawerKickPulseIsInTwoMillisecondUnits() {
        EscPos doc = new EscPos(CodePage.PC437, 48).openDrawer(EscPos.DrawerPin.PIN_5, Duration.ofMillis(100));

        assertThat(body(doc)).containsExactly(bytes(0x1B, 'p', 1, 50, 250));
    }

    @Test
    void cutFeedsToTheCutterThenCutsPartially() {
        assertThat(body(new EscPos(CodePage.PC437, 48).cut())).containsExactly(bytes(0x1D, 'V', 66, 3));
    }

    @Test
    void qrCodeStoresThePayloadWithItsLengthThenPrints() {
        byte[] body = body(new EscPos(CodePage.PC437, 48).qrCode("ABC", 6));

        assertThat(body)
                .containsSequence(bytes(0x1D, '(', 'k', 6, 0, '1', 'P', '0', 'A', 'B', 'C'))
                .containsSequence(bytes(0x1D, '(', 'k', 3, 0, '1', 'Q', '0'));
    }

    @Test
    void statusRequestIsDleEot() {
        assertThat(EscPos.statusRequest(4)).containsExactly(bytes(0x10, 0x04, 4));
    }

    static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] body(EscPos doc) {
        byte[] all = doc.toBytes();
        return Arrays.copyOfRange(all, PREAMBLE_LENGTH, all.length);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
