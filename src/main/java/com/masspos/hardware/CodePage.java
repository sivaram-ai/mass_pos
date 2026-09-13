package com.masspos.hardware;

import java.nio.charset.Charset;

/**
 * Printer character table (selected with {@code ESC t n}) paired with the Java charset that encodes
 * text for it. Only tables that practically every Epson-compatible printer ships are listed. No
 * text-mode table covers the rupee sign or Indic scripts; those need raster printing.
 */
public enum CodePage {
    PC437(0, "IBM437"),
    PC850(2, "IBM850"),
    PC858(19, "IBM00858"),
    WPC1252(16, "windows-1252");

    private final int escPosTable;
    private final String charsetName;

    CodePage(int escPosTable, String charsetName) {
        this.escPosTable = escPosTable;
        this.charsetName = charsetName;
    }

    public int escPosTable() {
        return escPosTable;
    }

    public Charset charset() {
        return Charset.forName(charsetName);
    }
}
