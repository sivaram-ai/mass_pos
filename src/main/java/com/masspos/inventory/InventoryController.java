package com.masspos.inventory;

import com.masspos.auth.RequiresRole;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/stock")
public class InventoryController {

    private final InventoryService inventory;
    private final ProductRepository products;

    public InventoryController(InventoryService inventory, ProductRepository products) {
        this.inventory = inventory;
        this.products = products;
    }

    /** @param active false for a stopped item, which the screen still shows, marked and dimmed */
    public record StockRow(UUID productId, String sku, String name, UnitOfMeasure unit, long stockMilli,
                           long sellingPricePaise, long mrpPaise, long stockValuePaise, boolean active) {

        static StockRow of(Product product, long stockMilli) {
            return new StockRow(product.getId(), product.getSku(), product.getName(), product.getUnit(), stockMilli,
                    product.getSellingPricePaise(), product.getMrpPaise(),
                    Math.round(stockMilli * product.getSellingPricePaise() / 1000.0), product.isActive());
        }
    }

    /**
     * A count put right by hand.
     *
     * @param deltaMilli how far the count moves. The reason settles which way it can go, so a
     *                   quantity typed the wrong way round is turned round rather than refused.
     * @param reason     why, which is what decides the movement that gets written
     * @param notes      whatever is worth keeping beside it, in the words of whoever typed it
     */
    public record CorrectionRequest(@NotNull UUID productId, long deltaMilli,
                                    @NotNull CorrectionReason reason,
                                    @Size(max = 160) String notes) {
    }

    /** One reason, as the dialog offers it. The labels live on the server, in the enum. */
    public record ReasonView(String name, String label, int sign) {
    }



    /**
     * Stock on hand for every product the shop still has, valued at the current selling price.
     * Stopped items are in the list: the goods are on the shelf whether or not they are being sold,
     * and leaving them out would make the value wrong and hide them from whoever has to count them.
     * Deleted items are gone. The screen sorts this itself, so code order is only the starting point.
     */
    @GetMapping
    public List<StockRow> stock() {
        Map<UUID, Long> onHand = inventory.onHandForAll();
        return products.findAll().stream()
                .filter(product -> !product.isDeleted())
                .map(product -> StockRow.of(product, onHand.getOrDefault(product.getId(), 0L)))
                .sorted(Comparator.comparing(StockRow::sku))
                .toList();
    }

    /**
     * Where an item's count came from: every movement ever recorded against it, newest first, each
     * with what the shelf held once it had been applied.
     *
     * <p>A count on its own is a number somebody has to take on trust. This is the answer to "it
     * says four and there are six" - the goods that came in, the bills that took them out, the
     * damage, and the corrections somebody made by hand, each with the till, the person and the
     * time. Nothing here is computed: it is the ledger, which is what the count is summed from.
     *
     * @param productId one item's whole history, with a running count; or every item's movements
     *                  over the last {@code days} when it is left out
     */
    @GetMapping("/history")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<StockMovementView> history(@RequestParam(required = false) UUID productId,
                                           @RequestParam(defaultValue = "30") int days) {
        if (productId != null) {
            return inventory.historyOf(productId);
        }
        return inventory.historySince(Instant.now().minus(Duration.ofDays(Math.max(1, Math.min(days, 370)))));
    }

    /** How far below zero this shop's counts have gone, for the screen to offer to put right. */
    @GetMapping("/below-empty")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public InventoryService.BelowEmpty belowEmpty() {
        return inventory.belowEmpty();
    }

    /**
     * Brings every count that is below zero up to zero. One correction each, with its reason, so
     * the history reads as the stock-take it is.
     */
    @PostMapping("/below-empty/correct")
    @RequiresRole({UserRole.MANAGER})
    public InventoryService.BelowEmpty bringUpToZero() {
        return inventory.bringUpToZero();
    }

    /** Why a count might be put right, for the dialog to offer. */
    @GetMapping("/correction-reasons")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<ReasonView> reasons() {
        return Arrays.stream(CorrectionReason.values())
                .map(reason -> new ReasonView(reason.name(), reason.label(), reason.sign()))
                .toList();
    }

    /**
     * A count put right by hand, which is the only way a manager moves one now.
     *
     * <p>There is deliberately no endpoint for goods arriving. Stock reaches the shelf through a
     * purchase order an administrator agreed to and nowhere else, so that a delivery is never the
     * first anybody hears of a purchase. What a shop already had on its shelves on the day it
     * started is a correction, with Opening stock as its reason - that is the shop saying what is
     * on the floor, which is a different thing from buying something.
     */
    @PostMapping("/corrections")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public StockRow correct(@Valid @RequestBody CorrectionRequest request) {
        inventory.correct(request.productId(), request.deltaMilli(), request.reason(), request.notes());
        return rowFor(request.productId());
    }

    private StockRow rowFor(UUID productId) {
        Product product = products.findById(productId).orElseThrow();
        return StockRow.of(product, inventory.onHand(productId));
    }
}
