package com.masspos.hardware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates this till's printer row on first start from whatever an installer put in
 * {@code pos.properties}, and says in the log where bills are going - the first thing to check when
 * a shop reports that nothing is printing.
 */
@Component
@Order(45)
public class PrinterBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PrinterBootstrap.class);

    private final PrinterSettingsService settings;
    private final PrinterProperties fromFile;
    private final ThermalPrinter printer;

    public PrinterBootstrap(PrinterSettingsService settings, PrinterProperties fromFile, ThermalPrinter printer) {
        this.settings = settings;
        this.fromFile = fromFile;
        this.printer = printer;
    }

    @Override
    public void run(ApplicationArguments args) {
        settings.seedOnce(PrinterSettingsForm.from(fromFile));
        if (printer.settings().type() == PrinterProperties.Type.FILE) {
            log.warn("No printer is set for this till: bills are being saved to {} instead of printed."
                    + " Choose one under Settings > Printer.", printer.target());
        } else {
            log.info("Printing on {}", printer.target());
        }
    }
}
