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
import com.masspos.vendor.VendorRepository;
import com.masspos.web.FieldRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
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

    public PurchaseOrderService(PurchaseOrderRepository orders, PurchaseOrderSequenceRepository sequences,
                                VendorRepository vendors, ProductRepository products,
                                RawMaterialRepository materials, InventoryService shelf,
                                RawMaterialService store, UserRepository users) {
        this.orders = orders;
        this.sequences = sequences;
        this.vendors = vendors;
        this.products = products;
        this.materials = materials;
        this.shelf = shelf;
        this.store = store;
        this.users = users;
    }

    /** What to put on an order: one item, how much of it, and what it is expected to cost. */
    public record Wanted(UUID productId, UUID rawMaterialId, long quantityMilli, long unitCostPaise) {
    }

    /** What actually turned up, per line, when the lorry is unloaded. */
    public record Arrived(UUID lineId, long quantityMilli, long unitCostPaise) {
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderView> search(LocalDate from, LocalDate to, UUID vendorId,
                                          PurchaseOrderStatus status) {
        return orders.search(from, to, vendorId, status).stream().map(PurchaseOrderView::of).toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrderView one(UUID id) {
        return PurchaseOrderView.of(order(id));
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderView> waitingForApproval() {
        return orders.waitingForApproval().stream().map(PurchaseOrderView::of).toList();
    }

    @Transactional
    public PurchaseOrderView create(UUID vendorId, LocalDate expectedOn, String note, List<Wanted> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            throw new FieldRejectedException("lines", "cannot be empty: say what the shop is ordering");
        }
        Vendor vendor = vendors.onTheList(vendorId).orElseThrow(() -> new IllegalArgumentException(
                "That vendor is not on this till any more. Refresh the screen and try again."));
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        PurchaseOrder order = new PurchaseOrder(nextNumber(today), vendor, today, expectedOn, note, currentUser());
        for (Wanted line : wanted) {
            if (line.quantityMilli() <= 0) {
                throw new FieldRejectedException("quantityMilli", "must be more than nothing");
            }
            order.add(new PurchaseOrderLine(productFor(line), materialFor(line), line.quantityMilli(),
                    line.unitCostPaise()));
        }
        return PurchaseOrderView.of(orders.save(order));
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
            if (quantity == 0) {
                continue;
            }
            String note = "Order " + order.getNumber();
            if (line.isFromTheStore()) {
                store.purchase(line.getRawMaterial().getId(), quantity, note, cost,
                        order.getVendor().getName(), order.getVendor().getId());
            } else {
                shelf.receive(line.getProduct().getId(), quantity, note, cost,
                        order.getVendor().getName(), order.getVendor().getId());
            }
        }
        return PurchaseOrderView.of(order);
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
