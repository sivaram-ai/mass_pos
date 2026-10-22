package com.masspos.common;

import java.util.ArrayList;
import java.util.List;

/**
 * The little of RFC 4180 a shop needs: Excel writes this, Excel reads it back, and so does
 * Notepad on a till with no Office on it.
 *
 * <p>Rows are written with CRLF, which is what Excel expects on Windows; both endings are accepted
 * when reading, along with the byte-order mark Excel puts at the front of what it saves. A field is
 * quoted only when it has to be - a comma, a quote, a line break, or spaces at either end - so an
 * ordinary catalogue stays readable in Notepad.
 */
public final class Csv {

    private static final String ROW_END = "\r\n";

    /**
     * Excel reads a UTF-8 file without this as if it were the Windows code page, which turns a
     * shop name in Tamil into rubbish. Put it in front of anything offered as a download.
     */
    public static final String EXCEL_BOM = "\uFEFF";

    private Csv() {
    }

    public static String write(List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            for (int column = 0; column < row.size(); column++) {
                if (column > 0) {
                    out.append(',');
                }
                out.append(field(row.get(column)));
            }
            out.append(ROW_END);
        }
        return out.toString();
    }

    /** Blank lines are dropped: Excel leaves one at the end of every file it saves. */
    public static List<List<String>> parse(String text) {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int at = 0; at < body.length(); at++) {
            char c = body.charAt(at);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (at + 1 < body.length() && body.charAt(at + 1) == '"') {
                    field.append('"');
                    at++;
                } else {
                    quoted = false;
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (c == '\r' && at + 1 < body.length() && body.charAt(at + 1) == '\n') {
                    at++;
                }
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        rows.removeIf(line -> line.stream().allMatch(String::isBlank));
        return rows;
    }

    private static String field(String value) {
        String text = value == null ? "" : value;
        boolean mustQuote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || !text.trim().equals(text);
        return mustQuote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }
}