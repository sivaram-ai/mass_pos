package com.masspos.billing;

import com.masspos.auth.RequiresRole;
import com.masspos.config.PosProperties;
import com.masspos.hardware.PrinterException;
import com.masspos.hardware.ThermalPrinter;
import com.masspos.receipt.ReceiptCopy;
import com.masspos.receipt.ReceiptService;
import com.masspos.user.UserRole;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class SaleController {

    private static final Logger log = LoggerFactory.getLogger(SaleController.class);

    private final SaleService sales;
    private final InvoiceRepository invoices;
    private final ReceiptService receipts;
    private final ThermalPrinter printer;
    private final PosProperties pos;

    public SaleController(SaleService sales, InvoiceRepository invoices, ReceiptService receipts,
                          ThermalPrinter printer, PosProperties pos) {
        this.sales = sales;
        this.invoices = invoices;
        this.receipts = receipts;
        this.printer = printer;
        this.pos = pos;
    }

    /**
     * @param printed    false when the receipt could not be printed; the sale is still recorded and
     *                   the UI offers a reprint
     * @param printError what went wrong, for the message on screen
     */
    public record SaleResponse(InvoiceView invoice, boolean printed, String printError) {
    }

    public record CancelRequest(@NotBlank @Size(max = 200) String reason) {
    }

    /** Takes the bill, then prints. A printer problem is reported but never undoes the sale. */
    @PostMapping("/api/sales")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public SaleResponse sell(@Valid @RequestBody SaleRequest request) {
        InvoiceView invoice = sales.sell(request);
        if (!request.print()) {
            return new SaleResponse(invoice, false, null);
        }
        try {
            printer.await(receipts.print(invoice.id(), ReceiptCopy.ORIGINAL, request.openDrawer()));
            return new SaleResponse(invoice, true, null);
        } catch (PrinterException e) {
            log.warn("Invoice {} was taken but could not be printed: {}", invoice.invoiceNumber(), e.getMessage());
            return new SaleResponse(invoice, false, e.getMessage());
        }
    }

    /** Live total for the cart on screen; issues nothing. */
    @PostMapping("/api/sales/quote")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    public QuotedCart quote(@Valid @RequestBody QuoteRequest request) {
        return sales.quote(request);
    }

    @GetMapping("/api/invoices")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER, UserRole.AUDITOR})
    @Transactional(readOnly = true)
    public List<InvoiceView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from == null ? LocalDate.now(com.masspos.common.IndiaTime.ZONE) : from;
        LocalDate end = to == null ? start : to;
        return invoices.findByInvoiceDateBetweenOrderByIssuedAtDesc(start, end).stream()
                .map(InvoiceView::of)
                .toList();
    }

    /**
     * The latest bill taken on this till, whatever the day, so the billing screen can show it and
     * reprint it after a restart. 204 before the till's first bill.
     */
    @GetMapping("/api/invoices/last")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER, UserRole.AUDITOR})
    @Transactional(readOnly = true)
    public ResponseEntity<InvoiceView> last() {
        return invoices.findFirstByTerminalCodeOrderByIssuedAtDesc(pos.terminal().code())
                .map(invoice -> ResponseEntity.ok(InvoiceView.of(invoice)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Every version of an edited bill, oldest first; just the bill when it was never edited. */
    @GetMapping("/api/invoices/{id}/history")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER, UserRole.AUDITOR})
    public List<InvoiceView> history(@PathVariable UUID id) {
        return sales.history(id);
    }

    @GetMapping("/api/invoices/{id}")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER, UserRole.AUDITOR})
    @Transactional(readOnly = true)
    public InvoiceView byId(@PathVariable UUID id) {
        return InvoiceView.of(invoices.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No invoice " + id)));
    }

    /**
     * A bill looked up by what a cashier types: its full number, or just its serial on this till.
     * Declared before {@code /{id}} for readability; Spring matches the literal path first anyway.
     */
    @GetMapping("/api/invoices/lookup")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER, UserRole.AUDITOR})
    public InvoiceView lookup(@RequestParam String number) {
        return sales.lookup(number);
    }

    /**
     * @param refundPaise    to hand back to the customer
     * @param collectedPaise taken on top of what was already paid
     */
    public record EditResponse(InvoiceView invoice, String replacedInvoiceNumber, long previousTotalPaise,
                               long refundPaise, long collectedPaise, boolean printed, String printError) {
    }

    /**
     * Edits today's bill: cancels it and issues the corrected bill in its place, settling only the
     * difference. A manager's job, like cancelling, since it changes money already taken.
     */
    @PostMapping("/api/invoices/{id}/replace")
    @RequiresRole({UserRole.MANAGER})
    public EditResponse replace(@PathVariable UUID id, @Valid @RequestBody EditRequest request) {
        SaleService.Edited edited = sales.replace(id, request);
        InvoiceView invoice = edited.invoice();
        String printError = null;
        if (request.print()) {
            try {
                printer.await(receipts.print(invoice.id(), ReceiptCopy.ORIGINAL, request.openDrawer()));
            } catch (PrinterException e) {
                log.warn("Edited bill {} was issued but could not be printed: {}", invoice.invoiceNumber(), e.getMessage());
                printError = e.getMessage();
            }
        }
        return new EditResponse(invoice, edited.replacedInvoiceNumber(), edited.previousTotalPaise(),
                edited.refundPaise(), edited.collectedPaise(), request.print() && printError == null, printError);
    }

    /** Only a manager may undo a sale, and only by cancelling it: the row is never removed. */
    @PostMapping("/api/invoices/{id}/cancel")
    @RequiresRole({UserRole.MANAGER})
    public InvoiceView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return sales.cancel(id, request.reason());
    }

}
