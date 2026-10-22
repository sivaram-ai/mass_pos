package com.masspos;

import com.masspos.catalog.ProductCsv;
import com.masspos.catalog.ProductController;
import com.masspos.settings.ShopSettingsCsv;
import com.masspos.settings.ShopSettingsForm;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sheets in docs/ are the first thing a shop opens, and the thing a setup person edits rather
 * than typing four hundred items into a screen. A column renamed here and forgotten there would
 * hand them a file the till refuses, which is why these are read by the real parser and put
 * through the same checks the screens use - not eyeballed.
 */
class SampleSheetsTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @Test
    void theSampleCatalogueIsOneThisTillWouldTake() throws IOException {
        List<ProductController.SaveProductRequest> items = ProductCsv.read(read("docs/sample-products.csv"));

        assertThat(items).hasSizeGreaterThan(5);
        for (ProductController.SaveProductRequest item : items) {
            assertThat(VALIDATOR.validate(item)).as(item.sku()).isEmpty();
            assertThat(item.sellingPricePaise()).as("%s is priced above its MRP", item.sku())
                    .isLessThanOrEqualTo(Math.max(item.mrpPaise(), item.sellingPricePaise()));
        }
        assertThat(items).anySatisfy(item -> assertThat(item.name()).contains(","));
        assertThat(items).extracting(ProductController.SaveProductRequest::sku).doesNotHaveDuplicates();
    }

    /** The screen refuses a selling price above the MRP, so a sample must not teach one. */
    @Test
    void nothingInTheSampleCatalogueIsPricedAboveItsMrp() throws IOException {
        for (ProductController.SaveProductRequest item : ProductCsv.read(read("docs/sample-products.csv"))) {
            if (item.mrpPaise() > 0) {
                assertThat(item.sellingPricePaise()).as(item.sku()).isLessThanOrEqualTo(item.mrpPaise());
            }
        }
    }

    @Test
    void theSampleShopDetailsAreOnesThisTillWouldSave() throws IOException {
        ShopSettingsForm shop = ShopSettingsCsv.read(read("docs/sample-company-details.csv"));

        assertThat(VALIDATOR.validate(shop)).isEmpty();
        assertThat(shop.legalName()).isNotBlank();
        assertThat(shop.address()).isNotEmpty();
        assertThat(shop.receiptFooter()).isNotEmpty();
    }

    private static String read(String path) throws IOException {
        Path file = Path.of(path);
        assertThat(file).as("shipped with the docs").exists();
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}