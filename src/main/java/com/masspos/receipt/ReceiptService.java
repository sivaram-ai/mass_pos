package com.masspos.receipt;

import com.masspos.billing.CreditNote;
import com.masspos.billing.Invoice;
import com.masspos.hardware.EscPos;
import com.masspos.hardware.PrinterProperties;
import com.masspos.hardware.ThermalPrinter;
import com.masspos.settings.ShopSettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class ReceiptService {

    private final EntityManager em;
    private final TransactionTemplate readOnlyTx;
    private final ReceiptFormatter formatter;
    private final ThermalPrinter printer;
    private final ShopSettingsService settings;

    public ReceiptService(EntityManager em, PlatformTransactionManager transactionManager, ReceiptFormatter formatter,
                          ThermalPrinter printer, ShopSettingsService settings) {
        this.em = em;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.formatter = formatter;
        this.printer = printer;
        this.settings = settings;
    }

    /**
     * Renders inside a short read transaction and prints after it has ended, so SQLite's single
     * write lock is never held while waiting on the printer.
     *
     * @param openDrawer honoured for the ORIGINAL copy only; a reprint never opens the drawer
     */
    public CompletableFuture<Void> print(UUID invoiceId, ReceiptCopy copy, boolean openDrawer) {
        EscPos receipt = readOnlyTx.execute(status -> {
            Invoice invoice = em.find(Invoice.class, invoiceId);
            if (invoice == null) {
                throw new EntityNotFoundException("That is not on this till any more. Refresh the screen and try again.");
            }
            return formatter.format(invoice, settings.currentOrEmpty(), printer.settings(), copy,
                    openDrawer && copy == ReceiptCopy.ORIGINAL);
        });
        return printer.print(receipt);
    }

    /**
     * The same bill, rendered for a counter's own printer instead of the server's.
     *
     * <p>A counter has no server, so the machine holding the database cannot reach the printer
     * standing on that counter. The bill is laid out here - once, by the code that lays out every
     * other bill - and the bytes go back to the counter, whose print agent writes them to the
     * printer beside it. The paper is described by the counter, because only the counter knows
     * how wide its own roll is; everything else about the bill is the shop's and comes from here.
     */
    public EscPos render(UUID invoiceId, ReceiptCopy copy, boolean openDrawer, PrinterProperties paper) {
        EscPos receipt = readOnlyTx.execute(status -> {
            Invoice invoice = em.find(Invoice.class, invoiceId);
            if (invoice == null) {
                throw new EntityNotFoundException("That is not on this till any more. Refresh the screen and try again.");
            }
            return formatter.format(invoice, settings.currentOrEmpty(), paper, copy,
                    openDrawer && copy == ReceiptCopy.ORIGINAL);
        });
        return receipt;
    }

    /** A return, rendered for a counter's own printer. */
    public EscPos renderCreditNote(UUID creditNoteId, ReceiptCopy copy, boolean openDrawer,
                                   PrinterProperties paper) {
        EscPos receipt = readOnlyTx.execute(status -> {
            CreditNote note = em.find(CreditNote.class, creditNoteId);
            if (note == null) {
                throw new EntityNotFoundException("That is not on this till any more. Refresh the screen and try again.");
            }
            return formatter.format(note, settings.currentOrEmpty(), paper, copy,
                    openDrawer && copy == ReceiptCopy.ORIGINAL);
        });
        return receipt;
    }

    /** A credit note, laid out like a bill; the drawer opens for the ORIGINAL copy only, to pay the refund out. */
    public CompletableFuture<Void> printCreditNote(UUID creditNoteId, ReceiptCopy copy, boolean openDrawer) {
        EscPos receipt = readOnlyTx.execute(status -> {
            CreditNote note = em.find(CreditNote.class, creditNoteId);
            if (note == null) {
                throw new EntityNotFoundException("That is not on this till any more. Refresh the screen and try again.");
            }
            return formatter.format(note, settings.currentOrEmpty(), printer.settings(), copy,
                    openDrawer && copy == ReceiptCopy.ORIGINAL);
        });
        return printer.print(receipt);
    }
}
