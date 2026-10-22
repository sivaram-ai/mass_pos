package com.masspos.catalog;

import com.masspos.web.FieldRejectedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sheet a shop edits. Paise and basis points are the database's business; a shopkeeper types
 * 125.50 and 5, and gets the same back.
 */
class ProductCsvTest {

    @Test
    void theCatalogueGoesOutInRupeesAndPercent() {
        String csv = ProductCsv.write(List.of(product("PG-100", "Sugar", 12550, 13000, 500)));

        assertThat(csv).startsWith("Code,Barcode,Name,HSN,Unit,Selling price,MRP,"
                + "Price includes tax,GST %,Cess %,Sell without stock\r\n");
        assertThat(csv).contains("PG-100,8901234,Sugar,1701,KGS,125.50,130.00,Yes,5,0,No");
    }

    @Test
    void whatGoesOutComesBackAsTheSameItem() {
        String csv = ProductCsv.write(List.of(product("PG-100", "Sugar, loose", 12550, 13000, 1200)));

        List<ProductController.SaveProductRequest> read = ProductCsv.read(csv);

        assertThat(read).singleElement().satisfies(item -> {
            assertThat(item.sku()).isEqualTo("PG-100");
            assertThat(item.name()).isEqualTo("Sugar, loose");
            assertThat(item.unit()).isEqualTo(UnitOfMeasure.KGS);
            assertThat(item.sellingPricePaise()).isEqualTo(12550);
            assertThat(item.mrpPaise()).isEqualTo(13000);
            assertThat(item.gstRateBp()).isEqualTo(1200);
            assertThat(item.taxInclusive()).isTrue();
            assertThat(item.billWithoutStock()).isFalse();
        });
    }

    @Test
    void columnsAreFoundByTheirHeadingNotTheirPosition() {
        String csv = "Name,Code,Unit,GST %,Something the shop keeps\r\nSugar,PG-100,KGS,5,ignored\r\n";

        assertThat(ProductCsv.read(csv)).singleElement().satisfies(item -> {
            assertThat(item.sku()).isEqualTo("PG-100");
            assertThat(item.name()).isEqualTo("Sugar");
            assertThat(item.gstRateBp()).isEqualTo(500);
        });
    }

    @Test
    void aRowThatCannotBeReadNamesItsLineAndNothingIsLoaded() {
        String csv = "Code,Name,Unit,Selling price,GST %\r\nPG-100,Sugar,KGS,125.50,5\r\nPG-101,Rice,SACK,abc,5\r\n";

        assertThatThrownBy(() -> ProductCsv.read(csv))
                .isInstanceOf(FieldRejectedException.class)
                .hasMessageContaining("was not loaded")
                .hasMessageContaining("row 3")
                .hasMessageContaining("SACK")
                .hasMessageContaining("abc");
    }

    @Test
    void aSheetWithoutTheCodeColumnIsRefused() {
        assertThatThrownBy(() -> ProductCsv.read("Name,Unit\r\nSugar,KGS\r\n"))
                .isInstanceOf(FieldRejectedException.class)
                .hasMessageContaining("Code column is missing");
    }

    @Test
    void anEmptyFileSaysWhereAGoodOneComesFrom() {
        assertThatThrownBy(() -> ProductCsv.read(""))
                .isInstanceOf(FieldRejectedException.class)
                .hasMessageContaining("Extract products");
    }

    private static ProductController.ProductView product(String sku, String name, long price, long mrp, int gstBp) {
        return new ProductController.ProductView(UUID.randomUUID(), sku, "8901234", name, "1701",
                UnitOfMeasure.KGS, price, mrp, true, gstBp, 0, false, true, 0);
    }
}