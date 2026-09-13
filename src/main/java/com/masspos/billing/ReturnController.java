package com.masspos.billing;

import com.masspos.auth.RequiresRole;
import com.masspos.common.IndiaTime;
import com.masspos.hardware.PrinterException;
import com.masspos.hardware.ThermalPrinter;
import com.masspos.receipt.ReceiptController.ReceiptRequest;
import com.masspos.receipt.ReceiptCopy;
import com.masspos.receipt.ReceiptService;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Returns: goods back on the shelf, money back to the customer, a credit note in the books. */
@RestController
public class ReturnController {

    private static final Logger log = LoggerFactory.getLogger(ReturnController.class);

    private final ReturnService returns;
    private final ReceiptService receipts;
    private final ThermalPrinter printer;

    public ReturnController(ReturnService returns, ReceiptService receipts, ThermalPrinter printer) {
        this.returns = returns;
        this.receipts = receipts;
        this.printer = printer;
    }

    public record ReturnResponse(CreditNoteView creditNote, boolean printed, String printError) {
    }

    /** Each line of a bill with how much of it can still come back. */
    @GetMapping("/api/invoices/{id}/returnable")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    public ReturnService.Returnable returnable(@PathVariable UUID id) {
        return returns.returnable(id);
    }

    @PostMapping("/api/returns/quote")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    public QuotedCart quote(@Valid @RequestBody ReturnRequest request) {
        return returns.quote(request);
    }

    /** Pays money out of the takings, so a manager's job, like cancelling a bill. */
    @PostMapping("/api/returns")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnResponse issue(@Valid @RequestBody ReturnRequest request) {
        CreditNoteView note = returns.issue(request);
        if (!request.print()) {
            return new ReturnResponse(note, false, null);
        }
        try {
            printer.await(receipts.printCreditNote(note.id(), ReceiptCopy.ORIGINAL, request.openDrawer()));
            return new ReturnResponse(note, true, null);
        } catch (PrinterException e) {
            log.warn("Return {} was issued but could not be printed: {}", note.creditNoteNumber(), e.getMessage());
            return new ReturnResponse(note, false, e.getMessage());
        }
    }

    @GetMapping("/api/returns")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER, UserRole.AUDITOR})
    public List<CreditNoteView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from == null ? LocalDate.now(IndiaTime.ZONE) : from;
        return returns.list(start, to == null ? start : to);
    }

    @GetMapping("/api/returns/{id}")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER, UserRole.AUDITOR})
    public CreditNoteView byId(@PathVariable UUID id) {
        return returns.byId(id);
    }

    @PostMapping("/api/returns/{id}/receipt")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void printReceipt(@PathVariable UUID id, @Valid @RequestBody ReceiptRequest request) {
        printer.await(receipts.printCreditNote(id, request.copy(), request.openDrawer()));
    }
}
