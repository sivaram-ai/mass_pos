package com.masspos.purchase;

import com.masspos.auth.AuthContext;
import com.masspos.billing.InvoiceNumbers;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.common.IndiaTime;
import com.masspos.inventory.InventoryService;
import com.masspos.rawmaterial.RawMaterial;
import com.masspos.rawmaterial.RawMaterialRepository;
import com.masspos.rawmaterial.RawMaterialService;
import com.masspos.user.User;
import com.masspos.user.UserRepository;
import com.masspos.vendor.Vendor;
import com.masspos.vendor.VendorPayment;
import com.masspos.vendor.VendorPaymentMode;
import com.masspos.vendor.VendorPaymentRepository;
import com.masspos.vendor.VendorRepository;
import com.masspos.vendor.VendorService;
import com.masspos.web.FieldRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ordering goods: asking, agreeing, and taking them in.
 *
 * <p>The whole point is the gap between the three. A manager writes down what the kitchen needs,
 * an administrator agrees to the money, and the counts move only when the lorry actually arrives -
 * with what actually came off it, which is not always what was ordered. Before this, a delivery
 * was the first anybody heard of a purchase.
 *
 * <p>Receiving does not write stock movements of its own. It calls the same two services the Stock
 * and Raw materials screens call, so a delivery against an order lands in the history looking
 * exactly like one somebody typed in by hand - with the vendor, the price and now the order
 * number on it.
 */
@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderSequenceRepository sequences;
    private final VendorRepository vendors;
    private final ProductRepository products;
    private final RawMaterialRepository materials;
    private final InventoryService shelf;
    private final RawMaterialService store;
    private final UserRepository users;
    private final VendorPaymentRepository paid;
    private final VendorService vendorPayments;
    private final PurchaseReturnRepository sentBack;

    public PurchaseOrderService(PurchaseOrderRepository orders, PurchaseOrderSequenceRepository sequences,
                                VendorRepository vendors, ProductRepository products,
                                RawMaterialRepository materials, InventoryService shelf,
                                RawMaterialService store, UserRepository users,
                                VendorPaymentRepository paid, VendorService vendorPayments,
                                PurchaseReturnRepository sentBack) {
        this.vendorPayments = vendorPayments;
        this.sentBack = sentBack;
        this.orders = orders;
        this.sequences = sequences;
        this.vendors = vendors;
        this.products = products;
        this.materials = materials;
        this.shelf = shelf;
        this.store = store;
        this.users = users;
        this.paid = paid;
    }

    /**
     * What to put on an order: one item, how much of it, and what it is expected to cost.
     *
     * @param itemName where neither id is given, what the thing is called - a one-off the shop
     *                 does not keep a count of
     * @param itemCode whatever the vendor calls it, if anything
     */
    public record Wanted(UUID productId, UUID rawMaterialId, String itemName, String itemCode,
                         long quantityMilli, long unitCostPaise) {
    }

    /** Money handed over as the order is written, which is ordinary with a vendor who wants it. */
    public record PaidNow(VendorPaymentMode mode, long amountPaise, String reference) {
    }

    /** What actually turned up, per line, when the lorry is unloaded. */
    public record Arrived(UUID lineId, long quantityMilli, long unitCostPaise) {
    }

    /**
     * Orders in a window. Everything else - the item, the person, the state of the money, a word
     * typed into a search box - is sifted on the screen, over the window it asked for, exactly as
     * the items list sifts the catalogue. A server that filtered as well would have to be asked
     * again on every keystroke, and would sort the wrong rows when it capped the answer.
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrderView> search(LocalDate from, LocalDate to, UUID vendorId,
                                          PurchaseOrderStatus status) {
        Map<UUID, Long> settled = allocated();
        Map<UUID, Long> back = whatWentBack();
        return orders.search(from, to, vendorId, status).stream()
                .map(order -> PurchaseOrderView.of(order, settled.getOrDefault(order.getId(), 0L),
                        back.getOrDefault(order.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrderView one(UUID id) {
        return view(order(id));
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderView> waitingForApproval() {
        Map<UUID, Long> settled = allocated();
        Map<UUID, Long> back = whatWentBack();
        return orders.waitingForApproval().stream()
                .map(order -> PurchaseOrderView.of(order, settled.getOrDefault(order.getId(), 0L),
                        back.getOrDefault(order.getId(), 0L)))
                .toList();
    }

    /** One order, with what has been paid against it and what has gone back off it. */
    private PurchaseOrderView view(PurchaseOrder order) {
        return PurchaseOrderView.of(order, paid.approvedForOrder(order.getId()),
                Math.round(sentBack.sentBackForOrder(order.getId()) / 1000.0));
    }

    /** Every order's returns in one query, so a list of two hundred costs one. */
    private Map<UUID, Long> whatWentBack() {
        Map<UUID, Long> back = new HashMap<>();
        for (Object[] row : sentBack.sentBackPerOrder()) {
            back.put((UUID) row[0], Math.round(((Number) row[1]).doubleValue() / 1000.0));
        }
        return back;
    }

    /** Every order's approved payments in one query, so a list of two hundred costs one. */
    private Map<UUID, Long> allocated() {
        Map<UUID, Long> settled = new HashMap<>();
        for (Object[] row : paid.approvedPerOrder()) {
            settled.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return settled;
    }

    /**
     * Writes one down.
     *
     * @param paidNow money handed over there and then. It is recorded against this order as any
     *                other payment to a vendor is, and waits for an administrator before it counts
     *                - the person who hands money over is rarely the person who decides it should
     *                be handed over, and that does not change because an order was written at the
     *                same moment.
     */
    @Transactional
    public PurchaseOrderView create(UUID vendorId, String vendorName, LocalDate expectedOn, String note,
                                    List<Wanted> wanted, List<PaidNow> paidNow) {
        return view(write(vendorId, vendorName, expectedOn, note, wanted, paidNow, ""));
    }

    /**
     * Changing an order is writing a new one, exactly as changing a bill is: this one is cancelled
     * with the new number written on it, the new one carries the old number, and what was agreed
     * to can never quietly differ from what was ordered. An order whose goods have arrived cannot
     * be changed - the counts have moved - and what has been paid against the old one follows the
     * order it was paid for.
     */
    @Transactional
    public PurchaseOrderView replace(UUID id, UUID vendorId, String vendorName, LocalDate expectedOn,
                                     String note, List<Wanted> wanted, List<PaidNow> paidNow) {
        PurchaseOrder old = order(id);
        // The number is reserved before the old order is cancelled, as an invoice's is: the
        // cancellation has to be able to name the order taking its place.
        PurchaseOrder written = write(vendorId, vendorName, expectedOn, note, wanted, paidNow,
                old.getNumber());
        old.replacedBy(currentUser(), Instant.now(), written.getNumber());
        for (VendorPayment carried : paid.allocatedTo(old.getId())) {
            carried.nowAgainst(written.getId());
        }
        return view(written);
    }

    private PurchaseOrder write(UUID vendorId, String vendorName, LocalDate expectedOn, String note,
                                List<Wanted> wanted, List<PaidNow> paidNow, String replaces) {
        if (wanted == null || wanted.isEmpty()) {
            throw new FieldRejectedException("lines", "cannot be empty: say what the shop is ordering");
        }
        // The vendor is optional: a shop buys from a man off a lorry as well as from its list.
        Vendor vendor = vendorId == null ? null
                : vendors.onTheList(vendorId).orElseThrow(() -> new IllegalArgumentException(
                        "That vendor is not on this till any more. Refresh the screen and try again."));
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        PurchaseOrder order = new PurchaseOrder(nextNumber(today), vendor,
                vendor != null ? vendor.getName() : vendorName, today, expectedOn, note, currentUser(),
                replaces);
        for (Wanted line : wanted) {
            if (line.quantityMilli() <= 0) {
                throw new FieldRejectedException("quantityMilli", "must be more than nothing");
            }
            order.add(lineFor(line));
        }
        PurchaseOrder written = orders.save(order);
        for (PaidNow handed : paidNow == null ? List.<PaidNow>of() : paidNow) {
            if (handed.amountPaise() > 0) {
                if (vendor == null) {
                    throw new FieldRejectedException("payments",
                            "cannot be recorded against a vendor who is not on the list. Add them on the "
                                    + "Vendors screen, or write the order and pay later.");
                }
                vendorPayments.pay(vendor.getId(), handed.amountPaise(), today, handed.mode(),
                        handed.reference(), "With order " + written.getNumber(), written.getId());
            }
        }
        return written;
    }

    /**
     * Everything that has happened to one order: back through the orders it replaced, on through
     * the ones that replaced it, and the goods sent back against any of them.
     *
     * @param versions every version it went through, oldest first
     * @param returns  what went back against any of them, oldest first
     */
    public record Story(List<PurchaseOrderView> versions, List<PurchaseReturnView> returns) {
    }

    /** Guards against a loop in hand-edited data; no shop edits an order sixty times. */
    private static final int MOST_EDITS = 60;

    @Transactional(readOnly = true)
    public Story history(UUID id) {
        PurchaseOrder opened = order(id);
        LinkedList<PurchaseOrder> chain = new LinkedList<>(List.of(opened));
        for (int step = 0; step < MOST_EDITS && !chain.getFirst().getReplacesOrderNumber().isEmpty(); step++) {
            PurchaseOrder earlier = orders.findByNumber(chain.getFirst().getReplacesOrderNumber()).orElse(null);
            if (earlier == null || chain.contains(earlier)) {
                break;
            }
            chain.addFirst(earlier);
        }
        for (int step = 0; step < MOST_EDITS; step++) {
            PurchaseOrder later = orders.replacing(chain.getLast().getNumber()).orElse(null);
            if (later == null || chain.contains(later)) {
                break;
            }
            chain.addLast(later);
        }
        List<PurchaseReturnView> returns = chain.stream()
                .flatMap(order -> sentBack.against(order.getId()).stream())
                .sorted(Comparator.comparing(PurchaseReturn::getReturnedOn))
                .map(PurchaseReturnView::of)
                .toList();
        return new Story(chain.stream().map(this::view).toList(), returns);
    }

    /** A line for something on one of the shop's lists, or for something it does not keep. */
    private PurchaseOrderLine lineFor(Wanted line) {
        if (line.productId() == null && line.rawMaterialId() == null) {
            return new PurchaseOrderLine(line.itemName(), line.itemCode(), line.quantityMilli(),
                    line.unitCostPaise());
        }
        return new PurchaseOrderLine(productFor(line), materialFor(line), line.quantityMilli(),
                line.unitCostPaise());
    }

    @Transactional
    public PurchaseOrderView approve(UUID id) {
        order(id).approve(currentUser(), Instant.now());
        return one(id);
    }

    @Transactional
    public PurchaseOrderView reject(UUID id, String why) {
        if (why == null || why.isBlank()) {
            throw new FieldRejectedException("decision", "cannot be empty: say why, so somebody can act on it");
        }
        order(id).reject(currentUser(), Instant.now(), why.trim());
        return one(id);
    }

    @Transactional
    public PurchaseOrderView cancel(UUID id, String why) {
        order(id).cancel(currentUser(), Instant.now(), why == null ? "" : why.trim());
        return one(id);
    }

    /**
     * The goods turn up. This is the only thing on an order that moves a count, and it moves it
     * through the same services everything else does.
     *
     * @param arrived what actually came, per line; a line left out of it came in full, at the
     *                cost on the order, which is the common case and should not need typing
     */
    @Transactional
    public PurchaseOrderView receive(UUID id, List<Arrived> arrived) {
        PurchaseOrder order = order(id);
        User by = currentUser();
        Map<UUID, Arrived> said = arrived == null ? Map.of()
                : arrived.stream().collect(java.util.stream.Collectors.toMap(Arrived::lineId, one -> one,
                        (first, second) -> second));

        order.receive(by, Instant.now());
        for (PurchaseOrderLine line : order.getLines()) {
            Arrived came = said.get(line.getId());
            long quantity = came == null ? line.getQuantityMilli() : Math.max(0, came.quantityMilli());
            long cost = came == null || came.unitCostPaise() < 0 ? line.getUnitCostPaise() : came.unitCostPaise();
            line.received(quantity);
            if (quantity == 0 || line.isOneOff()) {
                // A one-off has nothing to count: no item on either list carries it, which is what
                // made it a one-off. What it cost is on the order, and the vendor is owed for it.
                continue;
            }
            String note = "Order " + order.getNumber();
            // A vendor off the list is a name and no link, exactly as a delivery taken before the
            // shop kept a list is - the ledger has carried both since there were vendors at all.
            UUID vendorId = order.getVendor() == null ? null : order.getVendor().getId();
            if (line.isFromTheStore()) {
                store.purchase(line.getRawMaterial().getId(), quantity, note, cost,
                        order.getVendorName(), vendorId, order.getNumber());
            } else {
                shelf.receive(line.getProduct().getId(), quantity, note, cost,
                        order.getVendorName(), vendorId, order.getNumber());
            }
        }
        return view(order);
    }

    private Product productFor(Wanted line) {
        if (line.productId() == null) {
            return null;
        }
        return products.findById(line.productId()).orElseThrow(() -> new IllegalArgumentException(
                "That item is not on this till any more. Refresh the screen and try again."));
    }

    private RawMaterial materialFor(Wanted line) {
        if (line.rawMaterialId() == null) {
            return null;
        }
        return materials.onTheList(line.rawMaterialId()).orElseThrow(() -> new IllegalArgumentException(
                "That raw material is not on this till any more. Refresh the screen and try again."));
    }

    private PurchaseOrder order(UUID id) {
        return orders.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "That order is not on this till any more. Refresh the screen and try again."));
    }

    /** Reserved inside the transaction that writes the order, as invoice numbers are. */
    private String nextNumber(LocalDate today) {
        String year = InvoiceNumbers.financialYearCode(today);
        PurchaseOrderSequence sequence = sequences.findByFinancialYear(year)
                .orElseGet(() -> sequences.save(new PurchaseOrderSequence(year)));
        return "PO-%s-%04d".formatted(year, sequence.next());
    }

    private User currentUser() {
        return users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("Signed-in user has disappeared"));
    }
}
