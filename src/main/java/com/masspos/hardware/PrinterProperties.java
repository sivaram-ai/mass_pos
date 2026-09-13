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
}
