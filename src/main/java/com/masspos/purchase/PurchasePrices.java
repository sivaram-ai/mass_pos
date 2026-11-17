package com.masspos.purchase;

import com.masspos.catalog.UnitOfMeasure;
import com.masspos.inventory.InventoryLedgerEvent;
import com.masspos.inventory.InventoryLedgerRepository;
import com.masspos.rawmaterial.RawMaterialLedgerEvent;
import com.masspos.rawmaterial.RawMaterialLedgerRepository;
import com.masspos.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the shop has paid for one thing, over and over, with who it came from each time.
 *
 * <p>An item has one buying price on the screen - the last one anybody typed - and that is no use
 * at all for the question a shopkeeper actually asks, which is whether onions have got dearer and
 * which of two vegetable men is cheaper. The answer is in the deliveries, because a price is a
 * fact about a delivery and not about an item.
 *
 * <p>Read straight off the two ledgers, which is where a delivery is recorded, with the order
 * number carried on the movement so the approver can be named. Nothing is computed that the
 * ledgers do not already say.
 */
@Service
public class PurchasePrices {

    private final InventoryLedgerRepository shelf;
    private final RawMaterialLedgerRepository store;
    private final PurchaseOrderRepository orders;

    public PurchasePrices(InventoryLedgerRepository shelf, RawMaterialLedgerRepository store,
                          PurchaseOrderRepository orders) {
        this.shelf = shelf;
        this.store = store;
        this.orders = orders;
    }

    /**
     * One delivery, at the price it came in at.
     *
     * @param orderNumber the order it arrived against, or empty for a delivery a till took in by
     *                    hand before an order was the only way in
     * @param approvedBy  who agreed to the money, where an order says
     * @param by          who took the delivery in
     */
    public record PaidPrice(Instant when, String vendorName, long quantityMilli, UnitOfMeasure unit,
                            long unitCostPaise, long valuePaise, String orderNumber, String approvedBy,
                            String by) {
    }

    @Transactional(readOnly = true)
    public List<PaidPrice> forProduct(UUID productId) {
        List<InventoryLedgerEvent> deliveries = shelf.deliveriesOf(productId);
        Map<String, String> approvers = approversOf(deliveries.stream().map(InventoryLedgerEvent::getOrderNumber));
        List<PaidPrice> prices = new ArrayList<>(deliveries.size());
        for (InventoryLedgerEvent came : deliveries) {
            prices.add(new PaidPrice(came.getOccurredAt(), came.getVendorName(), came.getQuantityDeltaMilli(),
                    came.getProduct().getUnit(), came.getBuyingPricePaise(),
                    money(came.getQuantityDeltaMilli(), came.getBuyingPricePaise()), came.getOrderNumber(),
                    approvers.getOrDefault(came.getOrderNumber(), ""), who(came.getRecordedBy())));
        }
        return prices;
    }

    @Transactional(readOnly = true)
    public List<PaidPrice> forMaterial(UUID materialId) {
        List<RawMaterialLedgerEvent> deliveries = store.deliveriesOf(materialId);
        Map<String, String> approvers = approversOf(deliveries.stream().map(RawMaterialLedgerEvent::getOrderNumber));
        List<PaidPrice> prices = new ArrayList<>(deliveries.size());
        for (RawMaterialLedgerEvent came : deliveries) {
            prices.add(new PaidPrice(came.getOccurredAt(), came.getVendorName(), came.getQuantityDeltaMilli(),
                    came.getRawMaterial().getUnit(), came.getBuyingPricePaise(),
                    money(came.getQuantityDeltaMilli(), came.getBuyingPricePaise()), came.getOrderNumber(),
                    approvers.getOrDefault(came.getOrderNumber(), ""), who(came.getRecordedBy())));
        }
        return prices;
    }

    /** One query for every order named by the deliveries, rather than one per row. */
    private Map<String, String> approversOf(java.util.stream.Stream<String> numbers) {
        List<String> named = numbers.filter(number -> !number.isEmpty()).distinct().toList();
        if (named.isEmpty()) {
            return Map.of();
        }
        Map<String, String> approvers = new HashMap<>();
        for (PurchaseOrder order : orders.byNumbers(named)) {
            approvers.put(order.getNumber(), who(order.getDecidedBy()));
        }
        return approvers;
    }

    /** Milli-units times paise is a thousand times the money, so it is divided after the multiply. */
    private static long money(long quantityMilli, long unitCostPaise) {
        return Math.round(Math.abs(quantityMilli) * unitCostPaise / 1000.0);
    }

    private static String who(User user) {
        return user == null ? "" : user.getDisplayName();
    }
}
