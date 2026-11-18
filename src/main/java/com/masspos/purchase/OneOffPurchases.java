package com.masspos.purchase;

import com.masspos.catalog.UnitOfMeasure;
import com.masspos.vendor.AlsoBought;
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
