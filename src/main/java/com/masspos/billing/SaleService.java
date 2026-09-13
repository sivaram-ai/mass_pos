package com.masspos.billing;

import com.masspos.auth.AuthContext;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.common.IndiaTime;
import com.masspos.config.PosProperties;
import com.masspos.inventory.InventoryService;
import com.masspos.inventory.LedgerEventType;
import com.masspos.settings.ShopSettings;
import com.masspos.settings.ShopSettingsService;
import com.masspos.user.User;
import com.masspos.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Taking a bill. One transaction covers the invoice number, the invoice with its lines and
 * payments, and the stock movements, so a sale is either completely recorded or not at all: a
 * number is never consumed by a sale that did not happen.
 *
 * <p>Printing deliberately happens after this returns. The till must never hold SQLite's single
 * write lock while waiting on a printer, and a paper jam must not undo a paid sale.
 */
@Service
public class SaleService {

    private final InvoiceRepository invoices;
    private final InvoiceSequenceRepository sequences;
    private final ProductRepository products;
    private final UserRepository users;
    private final InventoryService inventory;
    private final ShopSettingsService settings;
    private final PosProperties pos;


    public SaleService(InvoiceRepository invoices, InvoiceSequenceRepository sequences, ProductRepository products,
                       UserRepository users, InventoryService inventory, ShopSettingsService settings,
                       PosProperties pos) {
        this.invoices = invoices;
        this.sequences = sequences;
        this.products = products;
        this.users = users;
        this.inventory = inventory;
        this.settings = settings;
        this.pos = pos;

    }

    /**
     * @return the issued invoice, read while the session is open so no caller meets a lazy proxy
     * @throws IllegalStateException when the shop's name or address is not configured yet (HTTP 409)
     */
    @Transactional
    public InvoiceView sell(SaleRequest request) {
        User cashier = users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("The signed-in user no longer exists"));
        ShopSettings shop = settings.currentOrEmpty();
        SellerDetails seller = SellerDetails.from(shop);
        String terminalCode = pos.terminal().code();
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        String financialYear = InvoiceNumbers.financialYearCode(today);
        InvoiceSequence sequence = sequences.findByTerminalCodeAndFinancialYear(terminalCode, financialYear)
                .orElseGet(() -> sequences.save(new InvoiceSequence(terminalCode, financialYear)));

        boolean gst = seller.isGstRegistered();
        if (!gst && blankToNull(request.buyerGstin()) != null) {
            throw new IllegalArgumentException("This shop has no GSTIN, so its bills carry no GST for a customer to"
                    + " claim. Leave the customer GSTIN empty.");
        }
        String placeOfSupply = placeOfSupply(request.placeOfSupply(), seller.getStateCode());
        boolean interState = gst && !placeOfSupply.equals(seller.getStateCode());

        Invoice invoice = new Invoice(terminalCode, sequence.next(), today, Instant.now(), seller, placeOfSupply,
                blankToNull(request.buyerGstin()), blankToNull(request.buyerName()), cashier);

        for (SaleRequest.Line line : request.lines()) {
            Product product = products.findById(line.productId())
                    .filter(Product::isActive)
                    .orElseThrow(() -> new IllegalArgumentException("No such product on sale: " + line.productId()));
            long unitPrice = line.unitPricePaise() == null ? product.getSellingPricePaise() : line.unitPricePaise();
            if (unitPrice <= 0) {
                throw new IllegalArgumentException("Enter the rate for " + product.getName() + " before taking the bill");
            }
            if (shop.isBlockNegativeStock() && inventory.onHand(product.getId()) < line.quantityMilli()) {
                throw new IllegalArgumentException("Not enough stock for " + product.getName());
            }
            int gstRate = gst ? product.getGstRateBp() : 0;
            int cessRate = gst ? product.getCessRateBp() : 0;
            GstAmounts amounts = GstCalculator.line(unitPrice, line.quantityMilli(), line.discountPaise(),
                    gstRate, cessRate, product.isTaxInclusive(), interState);
            invoice.addItem(new InvoiceItem(product, line.quantityMilli(), unitPrice, line.discountPaise(),
                    amounts.taxableValuePaise(), gstRate, cessRate,
                    amounts.cgstPaise(), amounts.sgstPaise(), amounts.igstPaise(), amounts.cessPaise()));
        }

        if (shop.isRoundInvoiceTotal()) {
            invoice.applyRoundOff(GstCalculator.roundOffToRupee(invoice.getGrandTotalPaise()));
        }

        long paid = request.payments().stream().mapToLong(SaleRequest.Payment::amountPaise).sum();
        if (paid != invoice.getGrandTotalPaise()) {
            throw new IllegalArgumentException("Payments add up to %d paise but the bill is %d paise"
                    .formatted(paid, invoice.getGrandTotalPaise()));
        }
        for (SaleRequest.Payment payment : request.payments()) {
            long tendered = payment.tenderedPaise() < payment.amountPaise() ? payment.amountPaise()
                    : payment.tenderedPaise();
            invoice.addPayment(new InvoicePayment(payment.mode(), payment.amountPaise(), tendered,
                    blankToNull(payment.reference())));
        }

        invoices.save(invoice);
        for (InvoiceItem item : invoice.getItems()) {
            inventory.record(item.getProduct().getId(), LedgerEventType.SALE, -item.getQuantityMilli(),
                    invoice.getId(), null);
        }
        return InvoiceView.of(invoice);
    }

    /**
     * Prices a cart without issuing anything, so the billing screen can show a running total that
     * is computed by exactly the code that will make the bill. Works before the shop's name and address
     * are filled in, so prices show on screen even during setup.
     */
    @Transactional(readOnly = true)
    public QuotedCart quote(QuoteRequest request) {
        ShopSettings shop = settings.currentOrEmpty();
        boolean gst = shop.isGstRegistered();
        String sellerState = shop.stateCode();
        String placeOfSupply = placeOfSupply(request.placeOfSupply(), sellerState);
        boolean interState = gst && !placeOfSupply.equals(sellerState);

        List<QuotedCart.QuotedLine> lines = new ArrayList<>();
        long taxable = 0;
        long cgst = 0;
        long sgst = 0;
        long igst = 0;
        long cess = 0;
        for (SaleRequest.Line line : request.lines()) {
            Product product = products.findById(line.productId())
                    .filter(Product::isActive)
                    .orElseThrow(() -> new IllegalArgumentException("No such product on sale: " + line.productId()));
            long unitPrice = line.unitPricePaise() == null ? product.getSellingPricePaise() : line.unitPricePaise();
            int gstRate = gst ? product.getGstRateBp() : 0;
            GstAmounts amounts = GstCalculator.line(unitPrice, line.quantityMilli(), line.discountPaise(),
                    gstRate, gst ? product.getCessRateBp() : 0, product.isTaxInclusive(), interState);
            lines.add(new QuotedCart.QuotedLine(product.getId(), product.getSku(), product.getName(),
                    product.getHsnCode(), product.getUnit(), line.quantityMilli(), unitPrice, line.discountPaise(),
                    gstRate, amounts.taxableValuePaise(), amounts.totalTaxPaise(), amounts.lineTotalPaise()));
            taxable += amounts.taxableValuePaise();
            cgst += amounts.cgstPaise();
            sgst += amounts.sgstPaise();
            igst += amounts.igstPaise();
            cess += amounts.cessPaise();
        }
        long total = taxable + cgst + sgst + igst + cess;
        long roundOff = shop.isRoundInvoiceTotal() ? GstCalculator.roundOffToRupee(total) : 0;
        return new QuotedCart(lines, interState, taxable, cgst, sgst, igst, cess, roundOff, total + roundOff);
    }

    /**
     * An issued invoice is never deleted or edited. Cancelling marks it and puts the stock back
     * with compensating ledger events, so the number stays in the series with its history intact.
     */
    @Transactional
    public InvoiceView cancel(UUID invoiceId, String reason) {
        Invoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("No invoice " + invoiceId));
        User by = users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("The signed-in user no longer exists"));
        invoice.cancel(by, reason, Instant.now());
        for (InvoiceItem item : invoice.getItems()) {
            inventory.record(item.getProduct().getId(), LedgerEventType.SALE_CANCELLED, item.getQuantityMilli(),
                    invoice.getId(), "Invoice " + invoice.getInvoiceNumber() + " cancelled");
        }
        return InvoiceView.of(invoice);
    }

    /**
     * The customer's state when one was given, else the seller's own. Both are empty for a shop
     * without a GSTIN, where the place of supply has no bearing on the bill.
     */
    private static String placeOfSupply(String requested, String sellerState) {
        if (sellerState.isEmpty()) {
            return "";
        }
        return requested == null || requested.isBlank() ? sellerState : requested;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
