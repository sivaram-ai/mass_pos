package com.masspos.hardware;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;

/**
 * The printer as the settings screen shows and sends it. One flat record covering every type: the
 * screen only shows the fields the chosen type uses, and the unused ones keep whatever was last in
 * them, so switching back to a printer you used before does not mean typing its port again.
 *
 * <p>Times are plain numbers here. A shop owner sets a 100 ms drawer pulse, not {@code PT0.1S}.
 */
public record PrinterSettingsForm(
        @NotNull PrinterProperties.Type type,
        @Size(max = 60) String serialPort,
        @Min(1200) @Max(1_000_000) int serialBaudRate,
        @NotNull PrinterProperties.FlowControl serialFlowControl,
        @Size(max = 100) String tcpHost,
        @Min(1) @Max(65535) int tcpPort,
        @Size(max = 200) String windowsPrinter,
        @Min(0) @Max(500) int windowsWidthMm,
        @Min(24) @Max(64) int columns,
        @NotNull CodePage codePage,
        @NotNull EscPos.DrawerPin drawerPin,
        @Min(1) @Max(510) int drawerPulseMillis,
        @Min(1) @Max(60) int timeoutSeconds) {

    public PrinterSettingsForm {
        serialPort = trimmed(serialPort);
        tcpHost = trimmed(tcpHost);
        windowsPrinter = trimmed(windowsPrinter);
    }

    /** The same settings in the shape the printer code works in. */
    public PrinterProperties toProperties() {
        return new PrinterProperties(type,
                new PrinterProperties.Serial(serialPort, serialBaudRate, serialFlowControl),
                new PrinterProperties.Tcp(tcpHost, tcpPort),
                new PrinterProperties.Windows(windowsPrinter, windowsWidthMm),
                columns, codePage, drawerPin,
                Duration.ofMillis(drawerPulseMillis), Duration.ofSeconds(timeoutSeconds));
    }

    /** Reads back what {@code pos.properties} holds, for the row created on the very first start. */
    public static PrinterSettingsForm from(PrinterProperties printer) {
        return new PrinterSettingsForm(printer.type(),
                printer.serial().port(), printer.serial().baudRate(), printer.serial().flowControl(),
                printer.tcp().host(), printer.tcp().port(),
                printer.windows().name(), printer.windows().widthMm(),
                printer.columns(), printer.codePage(), printer.drawerPin(),
                (int) Math.clamp(printer.drawerPulse().toMillis(), 1, 510),
                (int) Math.clamp(printer.timeout().toSeconds(), 1, 60));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
