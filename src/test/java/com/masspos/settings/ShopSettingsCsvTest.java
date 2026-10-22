package com.masspos.settings;

import com.masspos.web.FieldRejectedException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sixteen settings down the page rather than across it, because that is how a form reads. */
class ShopSettingsCsvTest {

    @Test
    void theShopGoesOutAndComesBackUnchanged() {
        ShopSettingsForm shop = shop();

        ShopSettingsForm read = ShopSettingsCsv.read(ShopSettingsCsv.write(shop));

        assertThat(read).isEqualTo(shop);
    }

    @Test
    void addressAndFooterLinesKeepTheirOrder() {
        String csv = ShopSettingsCsv.write(shop());

        assertThat(csv).contains("Address 1,12 Mount Road").contains("Address 2,Chennai 600002");
        assertThat(csv).contains("Receipt footer 1,Thank you").contains("Receipt footer 2,Visit again");
        assertThat(ShopSettingsCsv.read(csv).address()).containsExactly("12 Mount Road", "Chennai 600002");
        assertThat(ShopSettingsCsv.read(csv).receiptFooter()).containsExactly("Thank you", "Visit again");
    }

    @Test
    void aSettingThisTillDoesNotKnowIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> ShopSettingsCsv.read("Setting,Value\r\nLegal nmae,Mass Stores\r\n"))
                .isInstanceOf(FieldRejectedException.class)
                .hasMessageContaining("Legal nmae")
                .hasMessageContaining("was not loaded");
    }

    @Test
    void aFileOfSomethingElseEntirelySaysWhereAGoodOneComesFrom() {
        assertThatThrownBy(() -> ShopSettingsCsv.read("Setting,Value\r\n"))
                .isInstanceOf(FieldRejectedException.class)
                .hasMessageContaining("Extract company details");
    }

    @Test
    void yesAndNoComeBackAsTheSwitchesTheyAre() {
        String csv = ShopSettingsCsv.write(shop());

        assertThat(csv).contains("Round invoice total,Yes").contains("Block negative stock,No");
        assertThat(ShopSettingsCsv.read(csv).roundInvoiceTotal()).isTrue();
        assertThat(ShopSettingsCsv.read(csv).blockNegativeStock()).isFalse();
    }

    private static ShopSettingsForm shop() {
        return new ShopSettingsForm("Mass Stores Private Limited", "Mass Stores", "33AACCM1234F1Z5",
                List.of("12 Mount Road", "Chennai 600002"), "044 2345 6789", "shop@mass.example",
                "www.mass.example", "", "", "mass@upi", List.of("Thank you", "Visit again"),
                "GoGo Digital Services", "98400 00000", "service@gogo.example", true, false);
    }
}