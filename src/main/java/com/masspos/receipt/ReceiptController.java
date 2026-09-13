package com.masspos.receipt;

import com.masspos.auth.RequiresRole;
import com.masspos.hardware.ThermalPrinter;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
}
