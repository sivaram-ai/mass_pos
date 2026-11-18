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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Goods going back to the vendor.
 *
 * <p>The other end of a customer's return, and written the same way: the shop says what is going
 * back and how much it paid for it, the stock comes off there and then - the goods are on the
 * lorry - and what the vendor is owed drops by what went back.
 *
 * <p>Like a customer return it can be taken against the order the goods came in on, which fills
 * the lines in, or on its own for a sack somebody found spoiled three weeks later.
 */
@Service
public class PurchaseReturnService {

    private final PurchaseReturnRepository returns;
    private final PurchaseReturnSequenceRepository sequences;
    private final PurchaseOrderRepository orders;
    private final VendorRepository vendors;
    private final ProductRepository products;
    private final RawMaterialRepository materials;
    private final InventoryService shelf;
    private final RawMaterialService store;
    private final UserRepository users;

    public PurchaseReturnService(PurchaseReturnRepository returns, PurchaseReturnSequenceRepository sequences,
                                 PurchaseOrderRepository orders, VendorRepository vendors,
                                 ProductRepository products, RawMaterialRepository materials,
                                 InventoryService shelf, RawMaterialService store, UserRepository users) {
        this.returns = returns;
        this.sequences = sequences;
        this.orders = orders;
        this.vendors = vendors;
        this.products = products;
        this.materials = materials;
        this.shelf = shelf;
        this.store = store;
        this.users = users;
    }

    /** One thing going back: what it is, how much of it, and what the shop paid for one. */
    public record Going(UUID productId, UUID rawMaterialId, String itemName, String itemCode,
                        long quantityMilli, long unitCostPaise) {
    }

    @Transactional(readOnly = true)
    public List<PurchaseReturnView> search(LocalDate from, LocalDate to, UUID vendorId) {
        return returns.search(from, to, vendorId).stream().map(PurchaseReturnView::of).toList();
    }

    @Transactional(readOnly = true)
    public PurchaseReturnView one(UUID id) {
        return PurchaseReturnView.of(returns.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "That return is not on this till any more. Refresh the screen and try again.")));
    }

    /**
     * What can still go back off an order: what actually arrived, less whatever has already gone
     * back on an earlier return. A line with nothing left is still in the list, at zero, so the
     * screen can say so rather than quietly leaving it out.
     */
    @Transactional(readOnly = true)
    public List<PurchaseReturnView.CanGoBack> whatCanGoBack(UUID orderId) {
        PurchaseOrder order = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException(
                "That order is not on this till any more. Refresh the screen and try again."));
        if (order.getStatus() != PurchaseOrderStatus.RECEIVED) {
            throw new IllegalStateException("Nothing has come in on order " + order.getNumber() + " yet, so "
                    + "nothing can go back on it.");
        }
        Map<String, Long> alreadyBack = new HashMap<>();
        for (PurchaseReturn sentBack : returns.against(orderId)) {
            for (PurchaseReturnLine line : sentBack.getLines()) {
                alreadyBack.merge(itemKey(line.getProduct(), line.getRawMaterial(), line.getItemName()),
                        line.getQuantityMilli(), Long::sum);
            }
        }
        List<PurchaseReturnView.CanGoBack> left = new ArrayList<>();
        for (PurchaseOrderLine line : order.getLines()) {
            long back = alreadyBack.getOrDefault(
                    itemKey(line.getProduct(), line.getRawMaterial(), line.getItemName()), 0L);
            left.add(new PurchaseReturnView.CanGoBack(line.getId(), line.isFromTheStore(), line.isOneOff(),
                    line.isOneOff() ? null
                            : line.isFromTheStore() ? line.getRawMaterial().getId() : line.getProduct().getId(),
                    line.getItemCode(), line.getItemName(), line.getUnit(), line.getReceivedMilli(), back,
                    line.getUnitCostPaise()));
        }
        return left;
    }

    /**
     * Sends goods back.
     *
     * <p>The stock comes off here and not later: the goods are physically going out of the shop,
     * and a count that still carries them is wrong from this moment. It is capped at what is
     * there, as everything that takes stock off is - what the shop does not have cannot be put on
     * a lorry - and what actually went is what the return records.
     */
    @Transactional
    public PurchaseReturnView send(UUID vendorId, String vendorName, UUID againstOrderId, String reason,
                                   List<Going> going) {
        if (going == null || going.isEmpty()) {
            throw new FieldRejectedException("lines", "cannot be empty: say what is going back");
        }
        // Optional, as it is on an order: goods go back to the man off the lorry as well.
        Vendor vendor = vendorId == null ? null
                : vendors.onTheList(vendorId).orElseThrow(() -> new IllegalArgumentException(
                        "That vendor is not on this till any more. Refresh the screen and try again."));
        PurchaseOrder against = againstOrderId == null ? null : orders.findById(againstOrderId).orElse(null);
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        PurchaseReturn sentBack = new PurchaseReturn(nextNumber(today), vendor,
                vendor != null ? vendor.getName() : vendorName, againstOrderId,
                against == null ? "" : against.getNumber(), today, reason, currentUser());

        String note = "Sent back";
        boolean anything = false;
        for (Going line : going) {
            if (line.quantityMilli() <= 0) {
                continue;
            }
            long went;
            if (line.rawMaterialId() != null) {
                RawMaterial material = materialOrRefuse(line.rawMaterialId());
                went = store.sendBack(material.getId(), line.quantityMilli(), note, line.unitCostPaise(),
                        sentBack.getVendorName(), vendor == null ? null : vendor.getId(),
                        sentBack.getNumber());
                if (went > 0) {
                    sentBack.add(new PurchaseReturnLine(null, material, went, line.unitCostPaise()));
                }
            } else if (line.productId() != null) {
                Product product = productOrRefuse(line.productId());
                went = shelf.sendBack(product.getId(), line.quantityMilli(), note, line.unitCostPaise(),
                        sentBack.getVendorName(), vendor == null ? null : vendor.getId(),
                        sentBack.getNumber());
                if (went > 0) {
                    sentBack.add(new PurchaseReturnLine(product, null, went, line.unitCostPaise()));
                }
            } else {
                // A one-off: nothing counted it coming in, so nothing uncounts it going out. What
                // it was worth still comes off the vendor's balance, which is the whole point.
                went = line.quantityMilli();
                sentBack.add(new PurchaseReturnLine(line.itemName(), line.itemCode(), went,
                        line.unitCostPaise()));
            }
            anything |= went > 0;
        }
        if (!anything) {
            throw new IllegalStateException("None of that is on this till to send back. Check the counts "
                    + "and try again.");
        }
        return PurchaseReturnView.of(returns.save(sentBack));
    }

    /**
     * How a line is matched between an order and the returns against it. By the item where there
     * is one, and by the name where there is not - a one-off has nothing else to be known by, and
     * two one-offs on one order with the same name are the same thing twice.
     */
    private static String itemKey(Product product, RawMaterial material, String name) {
        if (product != null) {
            return "p:" + product.getId();
        }
        if (material != null) {
            return "r:" + material.getId();
        }
        return "n:" + name.toLowerCase();
    }

    private Product productOrRefuse(UUID productId) {
        return products.findById(productId).orElseThrow(() -> new IllegalArgumentException(
                "That item is not on this till any more. Refresh the screen and try again."));
    }

    private RawMaterial materialOrRefuse(UUID materialId) {
        return materials.onTheList(materialId).orElseThrow(() -> new IllegalArgumentException(
                "That item is not on this till any more. Refresh the screen and try again."));
    }

    /** Reserved inside the transaction that writes the return, as every other number here is. */
    private String nextNumber(LocalDate today) {
        String year = InvoiceNumbers.financialYearCode(today);
        PurchaseReturnSequence sequence = sequences.findByFinancialYear(year)
                .orElseGet(() -> sequences.save(new PurchaseReturnSequence(year)));
        return "PR-%s-%04d".formatted(year, sequence.next());
    }

    private User currentUser() {
        return users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("Signed-in user has disappeared"));
    }
}
