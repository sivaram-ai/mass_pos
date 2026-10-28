package com.masspos.counter;

import com.masspos.desktop.PosPaths;
import com.masspos.hardware.CodePage;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.PrinterProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;

/**
 * Everything a counter knows about itself: which counter it is, how it reaches the shop's server,
 * and what printer stands on it.
 *
 * <p>A plain properties file in the counter's own folder. It is the only state a counter has -
 * no database, no licence, nothing that cannot be retyped in a minute, which is the point of a
 * counter being a client. A machine that dies is replaced by installing the client and filling in
 * one screen.
 */
public final class CounterConfig {

    /** Beside the server's folder, never inside it: a counter has no server's data to mix with. */
    public static final String FOLDER = "GoRockitPOS-Counter";
    private static final String FILE = "counter.properties";

    /** How the counter decides where its server is. */
    public enum Find {
        /** Shout on the network and take the shop server that answers. */
        AUTOMATIC,
        /** The server computer's name or IP address, on the usual port. */
        ADDRESS,
        /** A whole address typed in, for anything unusual: another port, or a name behind a proxy. */
        URL
    }

    private String code = "";
    private String name = "";
    private Find find = Find.AUTOMATIC;
    private String address = "";
    private String url = "";
    /** Remembered from the last connection that worked, so one failed shout is not fatal. */
    private String lastServer = "";

    private PrinterProperties.Type printerType = PrinterProperties.Type.WINDOWS;
    private String serialPort = "";
    private int serialBaudRate = 9600;
    private PrinterProperties.FlowControl serialFlowControl = PrinterProperties.FlowControl.NONE;
    private String tcpHost = "";
    private int tcpPort = 9100;
    private String windowsPrinter = "";
    private int windowsWidthMm = 80;
    private int columns = 48;
    private CodePage codePage = CodePage.PC437;
    private EscPos.DrawerPin drawerPin = EscPos.DrawerPin.PIN_2;
    private int drawerPulseMillis = 100;
    private int timeoutSeconds = 5;
    private int agentPort = 8767;

    public static Path folder() {
        return PosPaths.base().resolve(FOLDER);
    }

    public static Path file() {
        return folder().resolve(FILE);
    }

    /** Whatever is on disk, with sensible answers for anything missing or unreadable. */
    public static CounterConfig load() {
        CounterConfig config = new CounterConfig();
        Properties saved = new Properties();
        if (Files.isRegularFile(file())) {
            try (InputStream in = Files.newInputStream(file())) {
                saved.load(in);
            } catch (IOException unreadable) {
                // A corrupt file must not stop the counter starting: the settings screen opens
                // instead, which is where somebody would have had to go anyway.
                return config;
            }
        }
        config.code = saved.getProperty("counter.code", "").trim().toUpperCase(Locale.ROOT);
        config.name = saved.getProperty("counter.name", "").trim();
        config.find = readEnum(saved, "server.find", Find.class, Find.AUTOMATIC);
        config.address = saved.getProperty("server.address", "").trim();
        config.url = saved.getProperty("server.url", "").trim();
        config.lastServer = saved.getProperty("server.last", "").trim();
        config.printerType = readEnum(saved, "printer.type", PrinterProperties.Type.class,
                PrinterProperties.Type.WINDOWS);
        config.serialPort = saved.getProperty("printer.serial.port", "").trim();
        config.serialBaudRate = readInt(saved, "printer.serial.baud-rate", 9600);
        config.serialFlowControl = readEnum(saved, "printer.serial.flow-control",
                PrinterProperties.FlowControl.class, PrinterProperties.FlowControl.NONE);
        config.tcpHost = saved.getProperty("printer.tcp.host", "").trim();
        config.tcpPort = readInt(saved, "printer.tcp.port", 9100);
        config.windowsPrinter = saved.getProperty("printer.windows.name", "").trim();
        config.windowsWidthMm = readInt(saved, "printer.windows.width-mm", 80);
        config.columns = readInt(saved, "printer.columns", 48);
        config.codePage = readEnum(saved, "printer.code-page", CodePage.class, CodePage.PC437);
        config.drawerPin = readEnum(saved, "printer.drawer-pin", EscPos.DrawerPin.class, EscPos.DrawerPin.PIN_2);
        config.drawerPulseMillis = readInt(saved, "printer.drawer-pulse-millis", 100);
        config.timeoutSeconds = readInt(saved, "printer.timeout-seconds", 5);
        config.agentPort = readInt(saved, "print-agent.port", 8767);
        return config;
    }

    public void save() throws IOException {
        Properties out = new Properties();
        out.setProperty("counter.code", code);
        out.setProperty("counter.name", name);
        out.setProperty("server.find", find.name());
        out.setProperty("server.address", address);
        out.setProperty("server.url", url);
        out.setProperty("server.last", lastServer);
        out.setProperty("printer.type", printerType.name());
        out.setProperty("printer.serial.port", serialPort);
        out.setProperty("printer.serial.baud-rate", Integer.toString(serialBaudRate));
        out.setProperty("printer.serial.flow-control", serialFlowControl.name());
        out.setProperty("printer.tcp.host", tcpHost);
        out.setProperty("printer.tcp.port", Integer.toString(tcpPort));
        out.setProperty("printer.windows.name", windowsPrinter);
        out.setProperty("printer.windows.width-mm", Integer.toString(windowsWidthMm));
        out.setProperty("printer.columns", Integer.toString(columns));
        out.setProperty("printer.code-page", codePage.name());
        out.setProperty("printer.drawer-pin", drawerPin.name());
        out.setProperty("printer.drawer-pulse-millis", Integer.toString(drawerPulseMillis));
        out.setProperty("printer.timeout-seconds", Integer.toString(timeoutSeconds));
        out.setProperty("print-agent.port", Integer.toString(agentPort));
        Files.createDirectories(folder());
        try (OutputStream stream = Files.newOutputStream(file())) {
            out.store(stream, "GO RockIt counter. Edited from the counter's own settings screen.");
        }
    }

    /** Enough to start: a counter number, and somewhere to look for a server. */
    public boolean readyToConnect() {
        return !code.isBlank() && switch (find) {
            case AUTOMATIC -> true;
            case ADDRESS -> !address.isBlank();
            case URL -> !url.isBlank();
        };
    }

    /** What the print agent builds its connection from. */
    public PrinterProperties printer() {
        return new PrinterProperties(printerType,
                new PrinterProperties.Serial(serialPort, serialBaudRate, serialFlowControl),
                new PrinterProperties.Tcp(tcpHost, tcpPort),
                new PrinterProperties.Windows(windowsPrinter, windowsWidthMm),
                columns, codePage, drawerPin, Duration.ofMillis(drawerPulseMillis),
                Duration.ofSeconds(timeoutSeconds));
    }

    private static int readInt(Properties saved, String key, int fallback) {
        try {
            return Integer.parseInt(saved.getProperty(key, "").trim());
        } catch (NumberFormatException missingOrNonsense) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E readEnum(Properties saved, String key, Class<E> type, E fallback) {
        try {
            return Enum.valueOf(type, saved.getProperty(key, "").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException missingOrNonsense) {
            return fallback;
        }
    }

    public String code() {
        return code;
    }

    public void code(String value) {
        this.code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public String name() {
        return name.isBlank() ? code : name;
    }

    public void name(String value) {
        this.name = value == null ? "" : value.trim();
    }

    public Find find() {
        return find;
    }

    public void find(Find value) {
        this.find = value;
    }

    public String address() {
        return address;
    }

    public void address(String value) {
        this.address = value == null ? "" : value.trim();
    }

    public String url() {
        return url;
    }

    public void url(String value) {
        this.url = value == null ? "" : value.trim();
    }

    public String lastServer() {
        return lastServer;
    }

    public void lastServer(String value) {
        this.lastServer = value == null ? "" : value.trim();
    }

    public int agentPort() {
        return agentPort;
    }

    public PrinterProperties.Type printerType() {
        return printerType;
    }

    public void printerType(PrinterProperties.Type value) {
        this.printerType = value;
    }

    public String serialPort() {
        return serialPort;
    }

    public void serialPort(String value) {
        this.serialPort = value == null ? "" : value.trim();
    }

    public String tcpHost() {
        return tcpHost;
    }

    public void tcpHost(String value) {
        this.tcpHost = value == null ? "" : value.trim();
    }

    public int tcpPort() {
        return tcpPort;
    }

    public void tcpPort(int value) {
        this.tcpPort = value;
    }

    public String windowsPrinter() {
        return windowsPrinter;
    }

    public void windowsPrinter(String value) {
        this.windowsPrinter = value == null ? "" : value.trim();
    }

    public int columns() {
        return columns;
    }

    public void columns(int value) {
        this.columns = value;
    }
}
