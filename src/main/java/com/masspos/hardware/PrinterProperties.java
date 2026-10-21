package com.masspos.hardware;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param columns     characters per line in Font A: 48 for 80 mm paper, 32 for 58 mm
 * @param drawerPulse drawer-kick pulse length; some solenoids need 200 ms
 * @param timeout     connect and write timeout for one job
 */
@Validated
@ConfigurationProperties(prefix = "pos.printer")
public record PrinterProperties(
        @NotNull Type type,
        @Valid @NotNull Serial serial,
        @Valid @NotNull Tcp tcp,
        @Valid @NotNull Windows windows,
        @Min(24) @Max(64) int columns,
        @NotNull CodePage codePage,
        @NotNull EscPos.DrawerPin drawerPin,
        @NotNull Duration drawerPulse,
        @NotNull Duration timeout) {

    public enum Type {
        /** RS-232 or a USB printer's virtual COM port, through jSerialComm. */
        SERIAL,
        /** LAN printer, raw TCP (port 9100). */
        TCP,
        /**
         * A printer installed in the operating system, reached through its driver. For tills
         * without a thermal printer: an ordinary inkjet or laser understands no ESC/POS, so the
         * receipt is re-rendered as text and sent to the spooler. No drawer kick, no paper status.
         */
        WINDOWS,
        /** No hardware: jobs are written to {@code <data-dir>/printer-out}. */
        FILE
    }

    /** Must match the printer's own settings (see its self-test page). */
    public enum FlowControl {
        NONE,
        RTS_CTS,
        DTR_DSR,
        /** Avoid: QR codes and other binary data can contain the XON/XOFF bytes themselves. */
        XON_XOFF
    }

    /** @param port e.g. COM3 on Windows, /dev/ttyUSB0 on Linux, /dev/cu.usbserial-XXXX on macOS */
    public record Serial(String port, @Positive int baudRate, @NotNull FlowControl flowControl) {
    }

    public record Tcp(String host, @Min(1) @Max(65535) int port) {
    }

    /**
     * @param name    exactly as the printer is listed in Windows' Printers &amp; scanners, e.g.
     *                "Canon G3080 series"; empty means whichever printer Windows calls the default
     * @param widthMm how wide the receipt is printed on the sheet: 80 keeps a bill a bill on A4
     *                instead of stretching it across the page; 0 fills the printable width
     */
    public record Windows(String name, @Min(0) @Max(500) int widthMm) {
    }
}
