package com.masspos.purchase;

import com.masspos.catalog.UnitOfMeasure;
import com.masspos.common.IndiaTime;
import com.masspos.vendor.AlsoBought;
import com.masspos.vendor.VendorHistory;
import com.masspos.vendor.VendorProfile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The part of a vendor's balance that no ledger can answer for: the things on received orders that
 * the shop does not keep a count of.
 *
 * <p>Only received orders count. An order that was written, or even approved, is not money owed to
 * anybody - the goods have to turn up first, which is the rule everywhere else in the purchase
 * package and the one a shopkeeper would state if asked.
 */
@Component
public class OneOffPurchases implements AlsoBought {

    private final PurchaseOrderRepository orders;

    public OneOffPurchases(PurchaseOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> perVendor() {
        Map<UUID, Long> spent = new HashMap<>();
        for (Object[] row : orders.oneOffsPerVendor()) {
            // Divided after the sum, as money always is here: milli-units times paise is a
            // thousand times the money.
            spent.put((UUID) row[0], Math.round(((Number) row[1]).doubleValue() / 1000.0));
        }
        return spent;
    }

    /**
     * The orders written to one vendor, newest first.
     *
     * <p>Dated by the day it was written rather than by any later decision: "when did we order
     * this" is the question, and the state beside it says what became of it.
     */
    @Override
    @Transactional(readOnly = true)
    public List<VendorHistory.Event> ordersOf(UUID vendorId) {
        List<VendorHistory.Event> written = new ArrayList<>();
        for (PurchaseOrder order : orders.everOrderedFrom(vendorId)) {
            written.add(new VendorHistory.Event(order.getId().toString(),
                    order.getOrderedOn().atStartOfDay(IndiaTime.ZONE).toInstant(),
                    VendorHistory.Kind.ORDERED, order.getNumber(), whatIsOnIt(order),
                    0, "", order.getValuePaise(),
                    order.getCreatedBy() == null ? "" : order.getCreatedBy().getDisplayName(),
                    WORDS.get(order.getStatus())));
        }
        return written;
    }

    /** The shop's own word for where an order has got to, the same five the screens use. */
    private static final Map<PurchaseOrderStatus, String> WORDS = Map.of(
            PurchaseOrderStatus.PENDING_APPROVAL, "Waiting",
            PurchaseOrderStatus.APPROVED, "Approved",
            PurchaseOrderStatus.RECEIVED, "Received",
            PurchaseOrderStatus.REJECTED, "Turned down",
            PurchaseOrderStatus.CANCELLED, "Called off");

    /** What the order was for, in as many words as one line of a history can hold. */
    private static String whatIsOnIt(PurchaseOrder order) {
        List<PurchaseOrderLine> lines = order.getLines();
        if (lines.isEmpty()) {
            return "Nothing on it";
        }
        String first = lines.get(0).getItemName();
        return lines.size() == 1 ? first : first + " and " + (lines.size() - 1) + " more";
    }

    @Override
    @Transactional(readOnly = true)
    public List<VendorProfile.Purchase> of(UUID vendorId) {
        List<VendorProfile.Purchase> bought = new ArrayList<>();
        for (PurchaseOrder order : orders.receivedFrom(vendorId)) {
            for (PurchaseOrderLine line : order.getLines()) {
                if (!line.isOneOff() || line.getReceivedMilli() <= 0) {
                    continue;
                }
                bought.add(new VendorProfile.Purchase(line.getId(),
                        order.getReceivedAt() == null
                                ? order.getOrderedOn().atStartOfDay().toInstant(ZoneOffset.UTC)
                                : order.getReceivedAt(),
                        false, line.getItemCode(), line.getItemName(), UnitOfMeasure.NOS,
                        line.getReceivedMilli(), line.getUnitCostPaise(), line.getReceivedValuePaise(),
                        "Order " + order.getNumber(), ""));
            }
        }
        return bought;
    }
}
