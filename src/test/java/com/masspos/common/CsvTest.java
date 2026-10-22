package com.masspos.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a shop opens in Excel, and what comes back when they save it there. */
class CsvTest {

    @Test
    void rowsEndTheWayExcelExpectsOnWindows() {
        String written = Csv.write(List.of(List.of("Code", "Name"), List.of("PG-100", "Sugar")));

        assertThat(written).isEqualTo("Code,Name\r\nPG-100,Sugar\r\n");
    }

    @Test
    void onlyWhatHasToBeQuotedIsQuoted() {
        String written = Csv.write(List.of(List.of("plain", "has,comma", "has\"quote", "has\nbreak", " spaced ")));

        assertThat(written).isEqualTo("plain,\"has,comma\",\"has\"\"quote\",\"has\nbreak\",\" spaced \"\r\n");
    }

    @Test
    void whateverIsWrittenReadsBackTheSame() {
        List<List<String>> rows = List.of(
                List.of("Code", "Name", "Note"),
                List.of("PG-100", "Sugar, loose", "1 kg \"fine\""),
                List.of("PG-101", "Rice\nsona masoori", ""));

        assertThat(Csv.parse(Csv.write(rows))).isEqualTo(rows);
    }

    @Test
    void theMarkExcelPutsInFrontIsNotPartOfTheFirstHeading() {
        List<List<String>> rows = Csv.parse(Csv.EXCEL_BOM + "Code,Name\r\nPG-100,Sugar\r\n");

        assertThat(rows.get(0)).containsExactly("Code", "Name");
    }

    @Test
    void bothLineEndingsWork_andBlankLinesAreIgnored() {
        List<List<String>> rows = Csv.parse("Code,Name\nPG-100,Sugar\r\n\r\n,\n");

        assertThat(rows).containsExactly(List.of("Code", "Name"), List.of("PG-100", "Sugar"));
    }
}