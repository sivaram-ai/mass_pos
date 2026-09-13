package com.masspos.receipt;

import com.masspos.billing.Invoice;
import com.masspos.hardware.EscPos;
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
                throw new EntityNotFoundException("No invoice " + invoiceId);
            }
            return formatter.format(invoice, settings.currentOrEmpty(), copy,
                    openDrawer && copy == ReceiptCopy.ORIGINAL);
        });
        return printer.print(receipt);
    }
}
