package com.masspos.counter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fazecast.jSerialComm.SerialPort;
import com.masspos.common.IndiaTime;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.PrinterProperties;
import com.masspos.hardware.PrinterSettingsForm;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * The printer standing on this counter, as the settings screen reads and writes it.
 *
 * <p>A counter's printer is its own, in the same way its keyboard shortcuts are: it is the machine
 * the paper comes out of. The server's {@code printer_settings} row is the server's printer, and
 * choosing a printer on a counter used to rewrite that row - so one counter changed where the back
 * office printed, and every other counter with it. The settings page on a counter therefore asks
 * this agent rather than the server, and what it saves lands in this machine's own
 * {@code counter.properties}.
 *
 * <p>The shapes here are deliberately the server's own ({@link PrinterSettingsForm} and a setup view
 * with the same field names), so the screen is one page with two places to send it rather than two
 * pages that drift.
 */
final class CounterPrinter {

    private static final ObjectMapper JSON = new ObjectMapper()
            // A newer screen sending a field this counter has not heard of must not stop it saving
            // the fields it does know.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final DateTimeFormatter PRINTED_AT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private CounterPrinter() {
    }

    /**
     * Everything the printer page needs about this counter: how it is set up, where that sends
     * bills, and what is plugged into this machine to choose from.
     */
    record Setup(PrinterSettingsForm printer, String target, List<SerialPortInfo> serialPorts,
                 List<String> installedPrinters) {
    }

    /** One COM port on this machine, named as the server names them. */
    record SerialPortInfo(String systemName, String description, String details) {
    }

    static Setup setupOf(CounterConfig config, Path spoolFolder) {
        return new Setup(config.printerForm(), config.printer().target(spoolFolder), serialPorts(),
                installedPrinters());
    }

    /**
     * The printer the screen chose.
     *
     * @throws IllegalArgumentException for anything the screen should not have sent, with the
     *                                 sentence shown on it - the agent has no validator and no
     *                                 error page, so the message is the whole of the answer
     */
    static PrinterSettingsForm read(byte[] body) {
        PrinterSettingsForm form;
        try {
            form = JSON.readValue(body, PrinterSettingsForm.class);
        } catch (IOException notAForm) {
            throw new IllegalArgumentException("that is not a printer this counter can be set to", notAForm);
        }
        if (form.type() == null || form.codePage() == null || form.drawerPin() == null
                || form.serialFlowControl() == null) {
            throw new IllegalArgumentException("the printer, code page, drawer pin and flow control are all needed");
        }
        atMost("the COM port", form.serialPort(), 60);
        atMost("the printer's address", form.tcpHost(), 100);
        atMost("the Windows printer name", form.windowsPrinter(), 200);
        between("the baud rate", form.serialBaudRate(), 1200, 1_000_000);
        between("the printer's port", form.tcpPort(), 1, 65535);
        between("the bill width", form.windowsWidthMm(), 0, 500);
        between("characters per line", form.columns(), 24, 64);
        between("the drawer pulse", form.drawerPulseMillis(), 1, 510);
        between("the time to give up after", form.timeoutSeconds(), 1, 60);
        return form;
    }

    /** Saves the printer and nothing else, so a failed write leaves the counter as it was. */
    static Setup save(CounterConfig config, Path spoolFolder, PrinterSettingsForm form) {
        PrinterSettingsForm was = config.printerForm();
        config.applyPrinter(form);
        try {
            config.save();
        } catch (IOException cannotWrite) {
            config.applyPrinter(was);
            throw new UncheckedIOException("Could not save the printer on this counter: "
                    + cannotWrite.getMessage(), cannotWrite);
        }
        return setupOf(config, spoolFolder);
    }

    static byte[] json(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /**
     * A page proving this counter's own printer works, printed on this counter's own paper. It
     * names the machine rather than only the shop: somebody holding two test pages has to be able
     * to tell which printer each came out of.
     */
    static EscPos testPage(CounterConfig config, Path spoolFolder, String shop) {
        PrinterProperties printer = config.printer();
        EscPos page = new EscPos(printer.codePage(), printer.columns());
        StringBuilder ruler = new StringBuilder();
        for (int i = 1; i <= page.columns(); i++) {
            ruler.append(i % 10);
        }
        return page.align(EscPos.Align.CENTER)
                .size(true, true).bold(true).line("TEST PAGE").size(false, false).bold(false)
                .wrapped(shop == null || shop.isBlank() ? "GO RockIt" : shop)
                .line("%s (%s)".formatted(config.name(), config.code()))
                .align(EscPos.Align.LEFT)
                .separator('=')
                .line(ruler.toString())
                .leftRight("Printer", printer.target(spoolFolder))
                .leftRight("Code page", printer.codePage().name())
                .leftRight("Columns", String.valueOf(printer.columns()))
                .leftRight("Printed", PRINTED_AT.format(ZonedDateTime.now(IndiaTime.ZONE)))
                .line("Rupee sign prints as: ₹")
                .separator('=')
                .cut();
    }

    private static List<SerialPortInfo> serialPorts() {
        return Arrays.stream(SerialPort.getCommPorts())
                .map(port -> new SerialPortInfo(port.getSystemPortName(), port.getDescriptivePortName(),
                        port.getPortDescription()))
                .toList();
    }

    private static List<String> installedPrinters() {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .map(PrintService::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static void atMost(String what, String value, int length) {
        if (value != null && value.length() > length) {
            throw new IllegalArgumentException(what + " is too long (at most " + length + " characters)");
        }
    }

    private static void between(String what, int value, int low, int high) {
        if (value < low || value > high) {
            throw new IllegalArgumentException(what + " has to be between " + low + " and " + high);
        }
    }
}
