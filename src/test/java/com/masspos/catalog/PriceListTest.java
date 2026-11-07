package com.masspos.catalog;

import com.masspos.hardware.CodePage;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.PrinterProperties;
import com.masspos.settings.ShopSettings;
import com.masspos.settings.ShopSettingsForm;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalogue on paper: one line an item, in the order the Products screen shows them. */
class PriceListTest {

    private static final LocalDateTime PRINTED_AT = LocalDateTime.of(2026, 11, 7, 22, 14);

    @Test
    void everyItemIsOneLineOfCodeNameAndRate() {
        List<String> lines = itemLines(product("10", "Tea 250g", 12_000, true));

        assertThat(lines).containsExactly("10    Tea 250g                      120.00");
    }

    @Test
    void aStoppedItemIsMarkedAfterItsRateOnTheSameLine() {
        List<String> lines = itemLines(
                product("2", "Sugar 1kg", 4_500, true),
                product("10", "Tea 250g", 12_000, false));

        // The mark hangs off the right of the rate, so the figures still line up under each other.
        assertThat(lines).containsExactly(
                "2     Sugar 1kg                  45.00",
                "10    Tea 250g                  120.00 (N)");
    }

    @Test
    void codesAreOrderedTheWayTheScreenOrdersThem() {
        List<String> codes = itemLines(
                product("PG-100", "Paracetamol", 2_500, true),
                product("10", "Tea", 12_000, true),
                product("PG-9", "Coffee", 19_000, true),
                product("2", "Sugar", 4_500, true))
                .stream().map(line -> line.split(" ")[0]).toList();

        // Digits compare as numbers: plain string order would give 10, 2, PG-100, PG-9.
        assertThat(codes).containsExactly("2", "10", "PG-9", "PG-100");
    }

    @Test
    void aNameTooLongForTheRoomIsCutRatherThanWrappedOntoASecondLine() {
        List<String> lines = itemLines(product("PG-100",
                "Paracetamol 500mg strip of ten tablets, generic", 2_500, true));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).hasSize(42).endsWith("25.00").contains("Paracetamol");
    }

    @Test
    void narrowPaperStillFitsTheRateAndTheMarkOnOneLine() {
        String printed = print(32, product("PG-100", "Paracetamol 500mg", 2_500, false));

        List<String> lines = itemsOf(printed);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).hasSize(32 - 0).endsWith("25.00 (N)");
        assertThat(lines.get(0)).startsWith("PG-100");
    }

    @Test
    void theListSaysHowManyItemsAndHowManyAreNoLongerSold() {
        String printed = print(42,
                product("1", "Sugar", 4_500, true),
                product("2", "Tea", 12_000, false));

        assertThat(printed).contains("2 items, 1 not sold")
                .contains("(N) is an item the shop has stopped selling");
    }

    /** Nothing says "not sold" on a list where everything is, so the legend stays off it. */
    @Test
    void aCatalogueWithNothingStoppedCarriesNoLegend() {
        String printed = print(42, product("1", "Sugar", 4_500, true));

        assertThat(printed).contains("1 item").doesNotContain("(N)");
    }

    @Test
    void theHeadingNamesTheShopAndWhenItWasPrinted() {
        String printed = print(42, product("1", "Sugar", 4_500, true));

        assertThat(printed).contains("PRICE LIST").contains("MASS MART").contains("07-11-2026 22:14");
    }

    private static List<String> itemLines(Product... products) {
        return itemsOf(print(42, products));
    }

    /** The item rows: what sits between the two rules, below the column headings. */
    private static List<String> itemsOf(String printed) {
        List<String> lines = Arrays.asList(printed.split("\n", -1));
        int first = lines.indexOf(lines.stream().filter(line -> line.startsWith("---")).findFirst().orElseThrow()) + 1;
        int last = lines.size() - 1;
        for (int at = first; at < lines.size(); at++) {
            if (lines.get(at).startsWith("---")) {
                last = at;
                break;
            }
        }
        return lines.subList(first, last);
    }

    private static String print(int columns, Product... products) {
        return PriceList.format(List.of(products), shop(), printer(columns), PRINTED_AT).plainText();
    }

    private static Product product(String sku, String name, long pricePaise, boolean active) {
        Product product = new Product(sku, name, "", UnitOfMeasure.NOS, pricePaise, pricePaise, true, 0);
        if (!active) {
            product.deactivate();
        }
        return product;
    }

    private static ShopSettings shop() {
        ShopSettings shop = new ShopSettings();
        shop.apply(new ShopSettingsForm("Mass Retail Private Limited", "MASS MART", "",
                List.of("12 MG Road, Camp"), "", "", "", "", "", "",
                List.of(), "", "", "", true, false, false, false));
        return shop;
    }

    private static PrinterProperties printer(int columns) {
        return new PrinterProperties(PrinterProperties.Type.FILE,
                new PrinterProperties.Serial("", 9600, PrinterProperties.FlowControl.NONE),
                new PrinterProperties.Tcp("", 9100), new PrinterProperties.Windows("", 80),
                columns, CodePage.PC437, EscPos.DrawerPin.PIN_2,
                Duration.ofMillis(100), Duration.ofSeconds(5));
    }
}
