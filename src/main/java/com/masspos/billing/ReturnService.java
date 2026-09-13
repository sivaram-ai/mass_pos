package com.masspos.billing;

import com.masspos.auth.AuthContext;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.catalog.UnitOfMeasure;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Taking goods back. A return against a bill refunds exactly what was paid for each item, a share
 * of that bill's own line figures, so the returns of a bill can never add up to more than the bill.
 * A return without the bill is priced like a sale at today's rates. Either way the stock goes back
 * and the money goes out in the same transaction that issues the credit note.
 */
@Service
public class ReturnService {

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final CreditNoteItemRepository creditNoteItems;
    private final CreditNoteSequenceRepository sequences;
    private final ProductRepository products;
    private final UserRepository users;
    private final InventoryService inventory;
    private final ShopSettingsService settings;
    private final PosProperties pos;

    public ReturnService(InvoiceRepository invoices, CreditNoteRepository creditNotes,
                         CreditNoteItemRepository creditNoteItems, CreditNoteSequenceRepository sequences,
                         ProductRepository products, UserRepository users, InventoryService inventory,
                         ShopSettingsService settings, PosProperties pos) {
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.creditNoteItems = creditNoteItems;
        this.sequences = sequences;
        this.products = products;
        this.users = users;
        this.inventory = inventory;
        this.settings = settings;
        this.pos = pos;
    }

    /**
     * What can still come back on each line of a bill.
     *
     * @param soldMilli     on the bill
     * @param returnedMilli on credit notes already issued against it
     */
    public record ReturnableLine(int lineNo, UUID productId, String sku, String name, UnitOfMeasure unit,
                                 long soldMilli, long returnedMilli, long unitPricePaise, long discountPaise,
                                 long lineTotalPaise) {
    }

    public record Returnable(InvoiceView invoice, List<ReturnableLine> lines) {
    }

    @Transactional(readOnly = true)
    public Returnable returnable(UUID invoiceId) {
        Invoice invoice = invoiceFor(invoiceId);
        Map<Integer, long[]> returned = returnedAgainst(invoice);
        List<ReturnableLine> lines = invoice.getItems().stream()
                .map(item -> new ReturnableLine(item.getLineNo(), item.getProduct().getId(), item.getSku(),
                        item.getProductName(), item.getUnit(), item.getQuantityMilli(),
                        returned.getOrDefault(item.getLineNo(), new long[6])[0], item.getUnitPricePaise(),
                        item.getDiscountPaise(), item.getLineTotalPaise()))
                .toList();
        return new Returnable(InvoiceView.of(invoice), lines);
    }

    /** The refund the screen shows while the return is being put together, by the code that issues it. */
    @Transactional(readOnly = true)
    public QuotedCart quote(ReturnRequest request) {
        ShopSettings shop = settings.currentOrEmpty();
        Priced priced = price(request, shop);
        QuotedCart.Builder cart = new QuotedCart.Builder(priced.interState());
        for (PricedLine line : priced.lines()) {
            cart.add(line.product(), line.quantityMilli(), line.unitPricePaise(), line.discountPaise(), line.gstRateBp(),
                    line.taxable(), line.cgst(), line.sgst(), line.igst(), line.cess());
        }
        return cart.build(shop.isRoundInvoiceTotal());
    }

    @Transactional
    public CreditNoteView issue(ReturnRequest request) {
        User cashier = users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("The signed-in user no longer exists"));
        ShopSettings shop = settings.currentOrEmpty();
        SellerDetails seller = SellerDetails.from(shop);
        Priced priced = price(request, shop);

        String terminalCode = pos.terminal().code();
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        String financialYear = InvoiceNumbers.financialYearCode(today);
        CreditNoteSequence sequence = sequences.findByTerminalCodeAndFinancialYear(terminalCode, financialYear)
                .orElseGet(() -> sequences.save(new CreditNoteSequence(terminalCode, financialYear)));
        Invoice original = priced.original();
        CreditNote note = new CreditNote(terminalCode, sequence.next(), today, Instant.now(), seller, original,
                priced.placeOfSupply(), original == null ? null : original.getBuyerGstin(),
                original == null ? null : original.getBuyerName(), SaleService.blankToNull(request.reason()), cashier);

        for (PricedLine line : priced.lines()) {
            note.addItem(new CreditNoteItem(line.product(), line.originalLine(), line.quantityMilli(),
                    line.unitPricePaise(), line.discountPaise(), line.taxable(), line.gstRateBp(), line.cessRateBp(),
                    line.cgst(), line.sgst(), line.igst(), line.cess()));
        }
        if (shop.isRoundInvoiceTotal()) {
            note.applyRoundOff(GstCalculator.roundOffToRupee(note.getGrandTotalPaise()));
        }

        long refunded = request.refundsOrNone().stream().mapToLong(ReturnRequest.Refund::amountPaise).sum();
        if (refunded != note.getGrandTotalPaise()) {
            throw new IllegalArgumentException("Refunds add up to %s but the return is %s"
                    .formatted(Money.rupees(refunded), Money.rupees(note.getGrandTotalPaise())));
        }
        request.refundsOrNone().forEach(refund -> note.addRefund(
                new CreditNoteRefund(refund.mode(), refund.amountPaise(), SaleService.blankToNull(refund.reference()))));

        creditNotes.save(note);
        for (CreditNoteItem item : note.getItems()) {
            inventory.record(item.getProduct().getId(), LedgerEventType.SALE_RETURN, item.getQuantityMilli(),
                    note.getId(), "Return " + note.getCreditNoteNumber());
        }
        return CreditNoteView.of(note);
    }

    @Transactional(readOnly = true)
    public List<CreditNoteView> list(LocalDate from, LocalDate to) {
        return creditNotes.findByNoteDateBetweenOrderByIssuedAtDesc(from, to).stream().map(CreditNoteView::of).toList();
    }

    @Transactional(readOnly = true)
    public CreditNoteView byId(UUID id) {
        return CreditNoteView.of(creditNotes.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No return " + id)));
    }

    // ---- Pricing ------------------------------------------------------------------------------------

    private record PricedLine(Product product, InvoiceItem originalLine, long quantityMilli, long unitPricePaise,
                              long discountPaise, int gstRateBp, int cessRateBp, long taxable, long cgst, long sgst,
                              long igst, long cess) {
    }

    private record Priced(Invoice original, String placeOfSupply, boolean interState, List<PricedLine> lines) {
    }

    private Priced price(ReturnRequest request, ShopSettings shop) {
        return request.againstInvoiceId() == null
                ? priceWithoutBill(request, shop)
                : priceAgainstBill(request, invoiceFor(request.againstInvoiceId()));
    }

    /** Today's shelf price and tax, as if the item were being sold now, within the shop's own state. */
    private Priced priceWithoutBill(ReturnRequest request, ShopSettings shop) {
        boolean gst = shop.isGstRegistered();
        List<PricedLine> lines = new ArrayList<>();
        for (ReturnRequest.Line line : request.lines()) {
            Product product = products.findById(line.productId())
                    .orElseThrow(() -> new IllegalArgumentException("No such product: " + line.productId()));
            long unitPrice = line.unitPricePaise() == null ? product.getSellingPricePaise() : line.unitPricePaise();
            if (unitPrice <= 0) {
                throw new IllegalArgumentException("Enter the rate paid for " + product.getName());
            }
            int gstRate = gst ? product.getGstRateBp() : 0;
            int cessRate = gst ? product.getCessRateBp() : 0;
            GstAmounts amounts = GstCalculator.line(unitPrice, line.quantityMilli(), line.discountPaise(), gstRate,
                    cessRate, product.isTaxInclusive(), false);
            lines.add(new PricedLine(product, null, line.quantityMilli(), unitPrice, line.discountPaise(), gstRate,
                    cessRate, amounts.taxableValuePaise(), amounts.cgstPaise(), amounts.sgstPaise(),
                    amounts.igstPaise(), amounts.cessPaise()));
        }
        return new Priced(null, shop.stateCode(), false, lines);
    }

    /**
     * A share of the bill line for each item back, in proportion to the quantity. Whatever is last to
     * come back takes exactly what is left of the line, so rounding never leaves a paisa behind.
     */
    private Priced priceAgainstBill(ReturnRequest request, Invoice original) {
        if (original.isCancelled()) {
            throw new IllegalArgumentException("Bill " + original.getInvoiceNumber()
                    + " is cancelled: its stock is already back and nothing was kept as paid");
        }
        // lineNo -> {quantity, taxable, cgst, sgst, igst, cess} already credited, then this request's too
        Map<Integer, long[]> credited = returnedAgainst(original);
        Map<Integer, InvoiceItem> billLines = new HashMap<>();
        original.getItems().forEach(item -> billLines.put(item.getLineNo(), item));

        List<PricedLine> lines = new ArrayList<>();
        for (ReturnRequest.Line line : request.lines()) {
            InvoiceItem sold = line.originalLineNo() == null ? null : billLines.get(line.originalLineNo());
            if (sold == null || !sold.getProduct().getId().equals(line.productId())) {
                throw new IllegalArgumentException("Pick the items being returned from bill "
                        + original.getInvoiceNumber() + "; an item not on it is returned without the bill");
            }
            long[] done = credited.computeIfAbsent(sold.getLineNo(), lineNo -> new long[6]);
            long left = sold.getQuantityMilli() - done[0];
            if (line.quantityMilli() > left) {
                throw new IllegalArgumentException("%s: only %s left to return on bill %s".formatted(
                        sold.getProductName(), Money.quantity(Math.max(left, 0)), original.getInvoiceNumber()));
            }
            long[] share = line.quantityMilli() == left
                    ? new long[] {sold.getTaxableValuePaise() - done[1], sold.getCgstPaise() - done[2],
                            sold.getSgstPaise() - done[3], sold.getIgstPaise() - done[4], sold.getCessPaise() - done[5]}
                    : new long[] {part(sold.getTaxableValuePaise(), line, sold), part(sold.getCgstPaise(), line, sold),
                            part(sold.getSgstPaise(), line, sold), part(sold.getIgstPaise(), line, sold),
                            part(sold.getCessPaise(), line, sold)};
            done[0] += line.quantityMilli();
            for (int i = 0; i < share.length; i++) {
                done[i + 1] += share[i];
            }
            lines.add(new PricedLine(sold.getProduct(), sold, line.quantityMilli(), sold.getUnitPricePaise(),
                    part(sold.getDiscountPaise(), line, sold), sold.getGstRateBp(), sold.getCessRateBp(), share[0],
                    share[1], share[2], share[3], share[4]));
        }
        return new Priced(original, original.getPlaceOfSupply(), original.isInterState(), lines);
    }

    /** Half-up share of a bill line figure for the quantity coming back. */
    private static long part(long lineFigure, ReturnRequest.Line line, InvoiceItem sold) {
        return (lineFigure * line.quantityMilli() + sold.getQuantityMilli() / 2) / sold.getQuantityMilli();
    }

    private Map<Integer, long[]> returnedAgainst(Invoice invoice) {
        Map<Integer, long[]> returned = new HashMap<>();
        for (Object[] row : creditNoteItems.returnedAgainst(invoice.getId())) {
            long[] sums = new long[6];
            for (int i = 0; i < 6; i++) {
                sums[i] = row[i + 1] == null ? 0 : ((Number) row[i + 1]).longValue();
            }
            returned.put((Integer) row[0], sums);
        }
        return returned;
    }

    private Invoice invoiceFor(UUID invoiceId) {
        return invoices.findById(invoiceId).orElseThrow(() -> new EntityNotFoundException("No bill " + invoiceId));
    }
}
