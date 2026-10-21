package com.masspos.hardware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Prints through a printer installed in the operating system - an ordinary inkjet or laser, say -
 * for tills that have no thermal printer. Such a printer understands no ESC/POS, so the job is
 * re-rendered as a page of text through its driver rather than sent as bytes.
 *
 * <p>Two things a thermal printer does are simply absent here: there is no cash drawer on the
 * printer's DK port, and the driver answers no real-time status request.
 */
public class WindowsPrinterConnection implements PrinterConnection {

    private static final Logger log = LoggerFactory.getLogger(WindowsPrinterConnection.class);
    private static final String DEFAULT_PRINTER = "the Windows default printer";

    private static final double POINTS_PER_MM = 72 / 25.4;

    private final String name;
    private final int widthMm;

    public WindowsPrinterConnection(PrinterProperties.Windows settings) {
        this.name = settings.name().trim();
        this.widthMm = settings.widthMm();
    }

    /** Receipt width on the sheet, never wider than the paper the driver says it can print on. */
    static double printWidth(double printable, int widthMm) {
        return widthMm <= 0 ? printable : Math.min(printable, widthMm * POINTS_PER_MM);
    }

    /**
     * Which printer "the default" actually is, so the settings screen can name it. Leaving the
     * choice to Windows is how a till ends up printing into the queue of whatever was default -
     * an unplugged printer, say - with nothing on screen to say so.
     */
    static String defaultPrinterName() {
        PrintService fallback = PrintServiceLookup.lookupDefaultPrintService();
        return fallback == null
                ? "no default printer is set in Windows"
                : fallback.getName() + " (the Windows default)";
    }

    @Override
    public void write(EscPos document) {
        List<EscPos.Line> lines = document.printedLines();
        if (lines.stream().allMatch(EscPos.Line::isEmpty)) {
            log.debug("Nothing to print on {}: a drawer kick or an empty job", describe());
            return;
        }
        PrinterJob job = PrinterJob.getPrinterJob();
        try {
            job.setPrintService(service());
            job.setJobName("GO RockIt receipt");
            // validatePage trims the generic default page down to what this printer can really reach.
            job.setPrintable(new ReceiptPage(lines, document.columns(), widthMm),
                    job.validatePage(job.defaultPage()));
            job.print();
        } catch (java.awt.print.PrinterException e) {
            throw new PrinterException("Printing on %s failed: %s".formatted(describe(), e.getMessage()), e);
        }
    }

    /** A driver printer has no status line back: the status screen shows it as not answering. */
    @Override
    public OptionalInt query(byte[] request, Duration timeout) {
        return OptionalInt.empty();
    }

    @Override
    public String describe() {
        return name.isEmpty() ? DEFAULT_PRINTER : name;
    }

    @Override
    public void close() {
    }

    private PrintService service() {
        if (name.isEmpty()) {
            PrintService fallback = PrintServiceLookup.lookupDefaultPrintService();
            if (fallback == null) {
                throw new PrinterException("No default printer is set in Windows. Installed: " + installed());
            }
            return fallback;
        }
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .filter(service -> service.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new PrinterException(
                        "No printer called \"%s\" is installed on this machine. Installed: %s"
                                .formatted(name, installed())));
    }

    private static String installed() {
        String names = Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .map(service -> "\"" + service.getName() + "\"")
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? "none" : names;
    }

    /** One receipt as pages the driver can print; laid out again for each page it asks for. */
    private record ReceiptPage(List<EscPos.Line> lines, int columns, int widthMm) implements Printable {

        @Override
        public int print(Graphics graphics, PageFormat page, int index) {
            ReceiptLayout layout = layout(page);
            if (index >= layout.pages()) {
                return NO_SUCH_PAGE;
            }
            Graphics2D g = (Graphics2D) graphics;
            g.translate(page.getImageableX(), page.getImageableY());
            layout.paint(g, index);
            return PAGE_EXISTS;
        }

        private ReceiptLayout layout(PageFormat page) {
            return new ReceiptLayout(lines, columns, printWidth(page.getImageableWidth(), widthMm),
                    page.getImageableHeight());
        }
    }
}
