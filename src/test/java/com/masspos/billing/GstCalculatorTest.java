package com.masspos.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GstCalculatorTest {

    /** The GST slabs in force: nil, 5, 12, 18 and 28 percent, in basis points. */
    private static final int[] SLABS = {0, 500, 1_200, 1_800, 2_800};

    @Test
    void shelfPriceIsWhatTheCustomerPays() {
        GstAmounts line = GstCalculator.line(10_000, 2_000, 0, 1_800, 0, true, false);

        assertThat(line.lineTotalPaise()).isEqualTo(20_000);
        assertThat(line.taxableValuePaise()).isEqualTo(16_949);
        assertThat(line.cgstPaise()).isEqualTo(1_525);
        assertThat(line.sgstPaise()).isEqualTo(1_526);
        assertThat(line.igstPaise()).isZero();
    }

    @Test
    void taxIsAddedOnTopOfAnExclusivePrice() {
        GstAmounts line = GstCalculator.line(10_000, 2_000, 0, 1_800, 0, false, false);

        assertThat(line.taxableValuePaise()).isEqualTo(20_000);
        assertThat(line.cgstPaise()).isEqualTo(1_800);
        assertThat(line.sgstPaise()).isEqualTo(1_800);
        assertThat(line.lineTotalPaise()).isEqualTo(23_600);
    }

    @Test
    void otherStateGetsIgstInsteadOfCgstAndSgst() {
        GstAmounts line = GstCalculator.line(10_000, 2_000, 0, 1_800, 0, true, true);

        assertThat(line.igstPaise()).isEqualTo(3_051);
        assertThat(line.cgstPaise()).isZero();
        assertThat(line.sgstPaise()).isZero();
        assertThat(line.lineTotalPaise()).isEqualTo(20_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 99, 100, 333, 1_999, 10_000, 123_456, 999_999})
    void inclusivePricingNeverDriftsFromThePrice(long unitPricePaise) {
        for (int rate : SLABS) {
            for (boolean interState : new boolean[] {false, true}) {
                GstAmounts line = GstCalculator.line(unitPricePaise, 3_000, 0, rate, 0, true, interState);

                assertThat(line.lineTotalPaise())
                        .as("rate %d, inter-state %s", rate, interState)
                        .isEqualTo(line.grossPaise());
                assertThat(line.taxableValuePaise() + line.totalTaxPaise()).isEqualTo(line.grossPaise());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 99, 333, 1_999, 123_457})
    void theTwoHalvesOfGstAlwaysReSum(long unitPricePaise) {
        for (int rate : SLABS) {
            GstAmounts line = GstCalculator.line(unitPricePaise, 1_500, 0, rate, 0, true, false);

            long gstCharged = line.lineTotalPaise() - line.taxableValuePaise() - line.cessPaise();
            assertThat(line.cgstPaise() + line.sgstPaise()).isEqualTo(gstCharged);
            // The odd paisa goes to SGST, so the halves differ by at most one.
            assertThat(line.sgstPaise() - line.cgstPaise()).isBetween(0L, 1L);
        }
    }

    @Test
    void cessSitsOnTopOfGst() {
        GstAmounts line = GstCalculator.line(10_000, 1_000, 0, 2_800, 1_200, false, false);

        assertThat(line.taxableValuePaise()).isEqualTo(10_000);
        assertThat(line.cessPaise()).isEqualTo(1_200);
        assertThat(line.cgstPaise() + line.sgstPaise()).isEqualTo(2_800);
        assertThat(line.lineTotalPaise()).isEqualTo(14_000);
    }

    @Test
    void discountIsTakenOffBeforeTax() {
        GstAmounts line = GstCalculator.line(10_000, 2_000, 5_000, 1_800, 0, true, false);

        assertThat(line.grossPaise()).isEqualTo(20_000);
        assertThat(line.lineTotalPaise()).isEqualTo(15_000);
        assertThat(line.taxableValuePaise()).isEqualTo(12_712);
    }

    @Test
    void weighedGoodsPriceByThePart() {
        // 1.250 kg at Rs 80.00 per kg
        GstAmounts line = GstCalculator.line(8_000, 1_250, 0, 500, 0, true, false);

        assertThat(line.grossPaise()).isEqualTo(10_000);
        assertThat(line.taxableValuePaise()).isEqualTo(9_524);
        assertThat(line.lineTotalPaise()).isEqualTo(10_000);
    }

    @Test
    void nilRatedGoodsCarryNoTax() {
        GstAmounts line = GstCalculator.line(4_500, 2_000, 0, 0, 0, true, false);

        assertThat(line.taxableValuePaise()).isEqualTo(9_000);
        assertThat(line.totalTaxPaise()).isZero();
    }

    @Test
    void billsAreRoundedToWholeRupees() {
        assertThat(GstCalculator.roundOffToRupee(12_345)).isEqualTo(-45);
        assertThat(GstCalculator.roundOffToRupee(12_355)).isEqualTo(45);
        assertThat(GstCalculator.roundOffToRupee(12_350)).isEqualTo(50);
        assertThat(GstCalculator.roundOffToRupee(12_300)).isZero();
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> GstCalculator.line(10_000, 0, 0, 1_800, 0, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quantity");
        assertThatThrownBy(() -> GstCalculator.line(10_000, 1_000, 10_001, 1_800, 0, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discount");
        assertThatThrownBy(() -> GstCalculator.line(10_000, 1_000, 0, 10_001, 0, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tax rates");
    }
}
