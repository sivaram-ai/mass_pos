package com.masspos.billing;

import com.masspos.auth.AuthContext;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.common.IndiaTime;
import com.masspos.common.Money;
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
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int MAX_EDITS = 100;

    private final InvoiceRepository invoices;
    private final InvoiceSequenceRepository sequences;
    private final CreditNoteRepository creditNotes;
    private final ProductRepository products;
    private final UserRepository users;
    private final InventoryService inventory;
    private final ShopSettingsService settings;
    private final PosProperties pos;

    public SaleService(InvoiceRepository invoices, InvoiceSequenceRepository sequences, CreditNoteRepository creditNotes,
                       ProductRepository products, UserRepository users, InventoryService inventory,
                       ShopSettingsService settings, PosProperties pos) {
        this.invoices = invoices;
        this.sequences = sequences;
        this.creditNotes = creditNotes;
        this.products = products;
        this.users = users;
        this.inventory = inventory;
        this.settings = settings;
        this.pos = pos;
    }

    /**
     * An edited bill: what it was, and what changed hands to settle the difference.
     *
     * @param refundPaise    handed back to the customer, when the new bill costs less
     * @param collectedPaise taken from the customer on top, when it costs more
     */
    public record Edited(InvoiceView invoice, String replacedInvoiceNumber, long previousTotalPaise, long refundPaise,
                         long collectedPaise) {
    }

    /**
     * @return the issued invoice, read while the session is open so no caller meets a lazy proxy
     * @throws IllegalStateException when the shop's name or address is not configured yet (HTTP 409)
     */
    @Transactional
    public InvoiceView sell(SaleRequest request) {
        User cashier = currentUser();
        ShopSettings shop = settings.currentOrEmpty();
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        Invoice invoice = build(cashier, shop, today, reserveNumber(today), request.lines(), request.buyerGstin(),
                request.buyerName(), request.placeOfSupply());

        long paid = request.payments().stream().mapToLong(SaleRequest.Payment::amountPaise).sum();
        if (paid != invoice.getGrandTotalPaise()) {
            throw new IllegalArgumentException("Payments add up to %s but the bill is %s"
                    .formatted(Money.rupees(paid), Money.rupees(invoice.getGrandTotalPaise())));
        }
        request.payments().forEach(payment -> invoice.addPayment(paymentOf(payment)));
        return issue(invoice);
    }

    /**
     * Edits a bill the only way an issued bill may change: it is cancelled, its stock comes back, and
     * a new bill with the corrected lines is issued in its place, all in one transaction. Money
     * already paid carries over to the new bill; only the difference changes hands.
     *
     * <p>Limited to today's bills. A bill from an earlier day may already be in a GST return, so
     * goods coming back against it are a return (credit note) instead.
     */
    @Transactional
    public Edited replace(UUID invoiceId, EditRequest request) {
        Invoice original = invoices.findById(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("No bill " + invoiceId));
        if (original.isCancelled()) {
            throw new IllegalArgumentException("Bill " + original.getInvoiceNumber() + " is cancelled, so it cannot"
                    + " be edited. Take a new bill instead.");
        }
        requireNoReturns(original, "edited");
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        if (!original.getInvoiceDate().equals(today)) {
            throw new IllegalArgumentException("Only today's bills can be edited. Bill " + original.getInvoiceNumber()
                    + " is from " + DAY.format(original.getInvoiceDate()) + ": take a return against it instead.");
        }
        User cashier = currentUser();
        ShopSettings shop = settings.currentOrEmpty();

        // The new number is reserved first so the old bill is cancelled once, with a reason naming its
        // replacement: a cancelled bill is frozen, so the reason cannot be filled in afterwards.
        long sequenceNo = reserveNumber(today);
        String replacementNumber = InvoiceNumbers.format(pos.terminal().code(),
                InvoiceNumbers.financialYearCode(today), sequenceNo);
        original.cancel(cashier, reasonFor(request.reason(), replacementNumber), Instant.now());
        // Its stock goes back before the new lines are checked against the shelf.
        restock(original);
        Invoice replacement = build(cashier, shop, today, sequenceNo, request.lines(), request.buyerGstin(),
                request.buyerName(), request.placeOfSupply());
        replacement.replaces(original.getInvoiceNumber());

        long previous = original.getGrandTotalPaise();
        long total = replacement.getGrandTotalPaise();
        long toCollect = Math.max(0, total - previous);
        List<SaleRequest.Payment> extra = request.paymentsOrNone();
        long collected = extra.stream().mapToLong(SaleRequest.Payment::amountPaise).sum();
        if (collected != toCollect) {
            throw new IllegalArgumentException(toCollect > 0
                    ? "Collect %s more: the edited bill is %s and %s was already paid"
                            .formatted(Money.rupees(toCollect - collected), Money.rupees(total), Money.rupees(previous))
                    : "Nothing more is due on the edited bill, so no payment can be taken for it");
        }

        // What was paid carries over in the order it was paid, up to the new total; the rest goes back.
        long remaining = total;
        for (InvoicePayment paid : original.getPayments()) {
            long carried = Math.min(paid.getAmountPaise(), remaining);
            if (carried > 0) {
                replacement.addPayment(new InvoicePayment(paid.getMode(), carried, carried, paid.getReference()));
                remaining -= carried;
            }
        }
        extra.forEach(payment -> replacement.addPayment(paymentOf(payment)));

        InvoiceView issued = issue(replacement);
        return new Edited(issued, original.getInvoiceNumber(), previous, Math.max(0, previous - total), collected);
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

        QuotedCart.Builder cart = new QuotedCart.Builder(interState);
        for (SaleRequest.Line line : request.lines()) {
            Product product = activeProduct(line.productId());
            long unitPrice = line.unitPricePaise() == null ? product.getSellingPricePaise() : line.unitPricePaise();
            int gstRate = gst ? product.getGstRateBp() : 0;
            GstAmounts amounts = GstCalculator.line(unitPrice, line.quantityMilli(), line.discountPaise(),
                    gstRate, gst ? product.getCessRateBp() : 0, product.isTaxInclusive(), interState);
            cart.add(product, line.quantityMilli(), unitPrice, line.discountPaise(), gstRate, amounts.taxableValuePaise(),
                    amounts.cgstPaise(), amounts.sgstPaise(), amounts.igstPaise(), amounts.cessPaise());
        }
        return cart.build(shop.isRoundInvoiceTotal());
    }

    /**
     * An issued invoice is never deleted or edited. Cancelling marks it and puts the stock back
     * with compensating ledger events, so the number stays in the series with its history intact.
     */
    @Transactional
    public InvoiceView cancel(UUID invoiceId, String reason) {
        Invoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("No invoice " + invoiceId));
        requireNoReturns(invoice, "cancelled");
        invoice.cancel(currentUser(), reason, Instant.now());
        restock(invoice);
        return InvoiceView.of(invoice);
    }

    /**
     * A bill by its number, or by just its serial on this till in the current financial year, so a
     * cashier can type "42" for T1-2627-00042.
     */
    @Transactional(readOnly = true)
    public InvoiceView lookup(String number) {
        String text = number == null ? "" : number.trim().toUpperCase(java.util.Locale.ROOT);
        String full = text.matches("\\d{1,9}")
                ? InvoiceNumbers.format(pos.terminal().code(),
                        InvoiceNumbers.financialYearCode(LocalDate.now(IndiaTime.ZONE)), Long.parseLong(text))
                : text;
        return invoices.findByInvoiceNumber(full)
                .map(InvoiceView::of)
                .orElseThrow(() -> new EntityNotFoundException("No bill numbered " + full));
    }

    /** The next bill number of this till. Only inside the transaction that issues the bill. */
    private long reserveNumber(LocalDate today) {
        String terminalCode = pos.terminal().code();
        String financialYear = InvoiceNumbers.financialYearCode(today);
        return sequences.findByTerminalCodeAndFinancialYear(terminalCode, financialYear)
                .orElseGet(() -> sequences.save(new InvoiceSequence(terminalCode, financialYear)))
                .next();
    }

    /**
     * Every version of a bill that was edited, oldest first: back through the bills it replaced and
     * on through the bills that replaced it. A bill never edited comes back on its own.
     */
    @Transactional(readOnly = true)
    public List<InvoiceView> history(UUID invoiceId) {
        Invoice opened = invoices.findById(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("No bill " + invoiceId));
        java.util.LinkedList<Invoice> chain = new java.util.LinkedList<>(List.of(opened));
        // Each step is one edit; the cap only guards against a loop in hand-edited data.
        for (int step = 0; step < MAX_EDITS && chain.getFirst().getReplacesInvoiceNumber() != null; step++) {
            Invoice earlier = invoices.findByInvoiceNumber(chain.getFirst().getReplacesInvoiceNumber()).orElse(null);
            if (earlier == null || chain.contains(earlier)) {
                break;
            }
            chain.addFirst(earlier);
        }
        for (int step = 0; step < MAX_EDITS; step++) {
            Invoice later = invoices.findFirstByReplacesInvoiceNumber(chain.getLast().getInvoiceNumber()).orElse(null);
            if (later == null || chain.contains(later)) {
                break;
            }
            chain.addLast(later);
        }
        return chain.stream().map(InvoiceView::of).toList();
    }

    /** Builds a complete, unpaid invoice under a reserved number: lines, tax and round-off. */
    private Invoice build(User cashier, ShopSettings shop, LocalDate today, long sequenceNo,
                          List<SaleRequest.Line> lines, String buyerGstin, String buyerName,
                          String requestedPlaceOfSupply) {
        SellerDetails seller = SellerDetails.from(shop);
        String terminalCode = pos.terminal().code();
        boolean gst = seller.isGstRegistered();
        if (!gst && blankToNull(buyerGstin) != null) {
            throw new IllegalArgumentException("This shop has no GSTIN, so its bills carry no GST for a customer to"
                    + " claim. Leave the customer GSTIN empty.");
        }
        String placeOfSupply = placeOfSupply(requestedPlaceOfSupply, seller.getStateCode());
        boolean interState = gst && !placeOfSupply.equals(seller.getStateCode());

        Invoice invoice = new Invoice(terminalCode, sequenceNo, today, Instant.now(), seller, placeOfSupply,
                blankToNull(buyerGstin), blankToNull(buyerName), cashier);

        for (SaleRequest.Line line : lines) {
            Product product = activeProduct(line.productId());
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
        return invoice;
    }

    private InvoiceView issue(Invoice invoice) {
        invoices.save(invoice);
        for (InvoiceItem item : invoice.getItems()) {
            inventory.record(item.getProduct().getId(), LedgerEventType.SALE, -item.getQuantityMilli(),
                    invoice.getId(), null);
        }
        return InvoiceView.of(invoice);
    }

    /**
     * Goods already returned against a bill have had their stock and money put back once. Cancelling
     * or editing the bill would do it a second time, so what is left of it can only come back as
     * further returns.
     */
    private void requireNoReturns(Invoice invoice, String action) {
        List<CreditNote> returns = creditNotes.findByOriginalInvoiceIdOrderByIssuedAtAsc(invoice.getId());
        if (!returns.isEmpty()) {
            throw new IllegalArgumentException("Bill %s has returns against it (%s), so it cannot be %s. Take a"
                    .formatted(invoice.getInvoiceNumber(),
                            String.join(", ", returns.stream().map(CreditNote::getCreditNoteNumber).toList()), action)
                    + " further return for anything else coming back.");
        }
    }

    /** Puts back the stock of a bill just cancelled, with compensating ledger events. */
    private void restock(Invoice invoice) {
        for (InvoiceItem item : invoice.getItems()) {
            inventory.record(item.getProduct().getId(), LedgerEventType.SALE_CANCELLED, item.getQuantityMilli(),
                    invoice.getId(), "Invoice " + invoice.getInvoiceNumber() + " cancelled");
        }
    }

    private static String reasonFor(String given, String replacementNumber) {
        String because = given == null || given.isBlank() ? "" : ": " + given.trim();
        return "Edited, replaced by " + replacementNumber + because;
    }

    private static InvoicePayment paymentOf(SaleRequest.Payment payment) {
        long tendered = Math.max(payment.tenderedPaise(), payment.amountPaise());
        return new InvoicePayment(payment.mode(), payment.amountPaise(), tendered, blankToNull(payment.reference()));
    }

    private Product activeProduct(UUID productId) {
        return products.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new IllegalArgumentException("No such product on sale: " + productId));
    }

    private User currentUser() {
        return users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("The signed-in user no longer exists"));
    }

    /**
     * The customer's state when one was given, else the seller's own. Both are empty for a shop
     * without a GSTIN, where the place of supply has no bearing on the bill.
     */
    static String placeOfSupply(String requested, String sellerState) {
        if (sellerState.isEmpty()) {
            return "";
        }
        return requested == null || requested.isBlank() ? sellerState : requested;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
