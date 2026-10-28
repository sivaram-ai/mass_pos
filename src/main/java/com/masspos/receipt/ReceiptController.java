package com.masspos.receipt;

import com.masspos.auth.RequiresRole;
import com.masspos.hardware.CodePage;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.PrinterProperties;
import com.masspos.hardware.ThermalPrinter;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
public class ReceiptController {

    private final ReceiptService receipts;
    private final ThermalPrinter printer;

    public ReceiptController(ReceiptService receipts, ThermalPrinter printer) {
        this.receipts = receipts;
        this.printer = printer;
    }

    public record ReceiptRequest(@NotNull ReceiptCopy copy, boolean openDrawer) {

        @AssertTrue(message = "the cash drawer opens only with the ORIGINAL receipt")
        public boolean isDrawerOnlyWithOriginal() {
            return !openDrawer || copy == ReceiptCopy.ORIGINAL;
        }
    }

    @PostMapping("/{invoiceId}/receipt")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void printReceipt(@PathVariable UUID invoiceId, @Valid @RequestBody ReceiptRequest request) {
        printer.await(receipts.print(invoiceId, request.copy(), request.openDrawer()));
    }

    /**
     * The bill as bytes, for a counter to print on its own printer.
     *
     * <p>Nothing is sent to the server's printer here. A counter asks for this instead of
     * {@code /receipt}, hands the bytes to the print agent on its own machine, and the bill comes
     * out beside the person who rang it up - with their drawer, not the back office's.
     */
    @PostMapping("/{invoiceId}/receipt-job")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    public PrintJob receiptJob(@PathVariable UUID invoiceId, @Valid @RequestBody CounterReceiptRequest request) {
        return PrintJob.of(receipts.render(invoiceId, request.copy(), request.openDrawer(), request.paper()));
    }

    /** The same for a return. */
    @PostMapping("/returns/{creditNoteId}/receipt-job")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    public PrintJob returnJob(@PathVariable UUID creditNoteId, @Valid @RequestBody CounterReceiptRequest request) {
        return PrintJob.of(receipts.renderCreditNote(creditNoteId, request.copy(), request.openDrawer(),
                request.paper()));
    }

    /**
     * A bill on its way to a counter, in both the shapes a printer might want it: the byte stream
     * a thermal printer executes, and the lines a driver printer renders. The counter's agent
     * picks whichever its own printer needs, so a shop can have a thermal printer on one counter
     * and an inkjet on the next without the server knowing or caring.
     */
    public record PrintJob(String escpos, List<EscPos.Line> lines) {

        static PrintJob of(EscPos job) {
            return new PrintJob(Base64.getEncoder().encodeToString(job.toBytes()), job.printedLines());
        }
    }

    /**
     * What the counter has to say about its own paper, and nothing else: how wide the roll is and
     * which character set the printer was built for. Everything else on the bill belongs to the
     * shop and is never taken from the machine asking.
     */
    public record CounterReceiptRequest(@NotNull ReceiptCopy copy, boolean openDrawer,
                                        @Min(24) @Max(64) int columns, @NotNull CodePage codePage,
                                        @NotNull EscPos.DrawerPin drawerPin, @Min(10) @Max(2000) int drawerPulseMillis) {

        @AssertTrue(message = "the cash drawer opens only with the ORIGINAL receipt")
        public boolean isDrawerOnlyWithOriginal() {
            return !openDrawer || copy == ReceiptCopy.ORIGINAL;
        }

        PrinterProperties paper() {
            // Only the layout fields matter: the bytes travel back to the counter, which opens
            // its own port. A transport here would be the server's, and wrong by definition.
            return new PrinterProperties(PrinterProperties.Type.FILE,
                    new PrinterProperties.Serial("", 9600, PrinterProperties.FlowControl.NONE),
                    new PrinterProperties.Tcp("", 9100),
                    new PrinterProperties.Windows("", 80),
                    columns, codePage, drawerPin, Duration.ofMillis(drawerPulseMillis), Duration.ofSeconds(5));
        }
    }
}
