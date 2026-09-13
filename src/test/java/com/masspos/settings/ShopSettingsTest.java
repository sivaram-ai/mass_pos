package com.masspos.settings;

import com.masspos.billing.SellerDetails;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShopSettingsTest {

    @Test
    void aFreshShopKnowsWhatItStillNeedsBeforeBilling() {
        ShopSettings shop = new ShopSettings();

        assertThat(shop.isReadyToInvoice()).isFalse();
        assertThat(shop.missingForInvoicing()).containsExactly("legal name", "address");
        assertThatThrownBy(() -> SellerDetails.from(shop))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legal name");
    }

    @Test
    void aShopWithoutAGstinCanStillBillButIsNotGstRegistered() {
        ShopSettings shop = new ShopSettings();
        shop.apply(form("Mass Tiffin Centre", "NEW MASS", "", List.of("4 Bazaar Street", "Madurai")));

        SellerDetails seller = SellerDetails.from(shop);

        assertThat(shop.isReadyToInvoice()).isTrue();
        assertThat(shop.isGstRegistered()).isFalse();
        assertThat(seller.isGstRegistered()).isFalse();
        assertThat(seller.getGstin()).isEmpty();
        assertThat(seller.getStateCode()).isEmpty();
        assertThat(configured().isGstRegistered()).isTrue();
    }

    @Test
    void filledInSettingsBecomeTheSellerOnTheInvoice() {
        ShopSettings shop = configured();

        SellerDetails seller = SellerDetails.from(shop);

        assertThat(seller.getLegalName()).isEqualTo("Mass Retail Private Limited");
        assertThat(seller.getTradeName()).isEqualTo("MASS MART");
        assertThat(seller.getStateCode()).isEqualTo("27");
        assertThat(seller.getAddressLines()).containsExactly("12 MG Road, Camp", "Pune 411001");
        assertThat(seller.getFssaiLicense()).isEqualTo("11521999000123");
    }

    @Test
    void normalisesWhatWasTypedIn() {
        ShopSettings shop = new ShopSettings();
        shop.apply(form(" Mass Retail Private Limited ", "", " 27aapfu0939f1zv ",
                List.of(" 12 MG Road, Camp ", "  ", "Pune 411001")));

        assertThat(shop.getGstin()).isEqualTo("27AAPFU0939F1ZV");
        assertThat(shop.addressLines()).containsExactly("12 MG Road, Camp", "Pune 411001");
        // Trade name falls back to the legal name wherever the shop's name is shown.
        assertThat(shop.displayName()).isEqualTo("Mass Retail Private Limited");
        assertThat(shop.stateCode()).isEqualTo("27");
    }

    @Test
    void receiptFooterKeepsItsLines() {
        ShopSettings shop = configured();

        assertThat(shop.receiptFooter()).containsExactly("Exchange within 7 days", "Thank you!");
    }

    static ShopSettings configured() {
        ShopSettings shop = new ShopSettings();
        shop.apply(form("Mass Retail Private Limited", "MASS MART", "27AAPFU0939F1ZV",
                List.of("12 MG Road, Camp", "Pune 411001")));
        return shop;
    }

    private static ShopSettingsForm form(String legalName, String tradeName, String gstin, List<String> address) {
        return new ShopSettingsForm(legalName, tradeName, gstin, address, "020-5550100", "shop@example.com",
                "www.example.com", "U52100MH2020PTC123456", "11521999000123", "massmart@okicici",
                List.of("Exchange within 7 days", "Thank you!"), "Ravi Systems", "98200-00000",
                "support@example.com", true, false);
    }
}
