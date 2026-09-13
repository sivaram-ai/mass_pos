package com.masspos.hardware;

import com.masspos.config.PosProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PrinterConfig {

    /**
     * Never fails on hardware problems: a misconfigured or absent printer must not stop the till
     * from starting or selling. Errors surface when a job is sent.
     */
    @Bean(destroyMethod = "close")
    public PrinterConnection printerConnection(PrinterProperties printer, PosProperties pos) {
        return switch (printer.type()) {
            case SERIAL -> new SerialPrinterConnection(printer.serial(), printer.timeout());
            case TCP -> new TcpPrinterConnection(printer.tcp(), printer.timeout());
            case FILE -> new FilePrinterConnection(pos.dataDir().resolve("printer-out"));
        };
    }
}
