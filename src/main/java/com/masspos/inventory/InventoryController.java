package com.masspos.inventory;

import com.masspos.auth.RequiresRole;
import com.masspos.catalog.Product;
import com.masspos.catalog.ProductRepository;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    public record MovementRequest(@NotNull UUID productId, @Positive long quantityMilli,
                                  @Size(max = 200) String note) {
    }

    public record AdjustmentRequest(@NotNull UUID productId, long deltaMilli, @Size(max = 200) String note) {
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

    @PostMapping("/receipts")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public StockRow receive(@Valid @RequestBody MovementRequest request) {
        inventory.receive(request.productId(), request.quantityMilli(), request.note());
        return rowFor(request.productId());
    }

    @PostMapping("/adjustments")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public StockRow adjust(@Valid @RequestBody AdjustmentRequest request) {
        inventory.adjust(request.productId(), request.deltaMilli(), request.note());
        return rowFor(request.productId());
    }

    @PostMapping("/damages")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public StockRow damage(@Valid @RequestBody MovementRequest request) {
        inventory.damage(request.productId(), request.quantityMilli(), request.note());
        return rowFor(request.productId());
    }

    private StockRow rowFor(UUID productId) {
        Product product = products.findById(productId).orElseThrow();
        return StockRow.of(product, inventory.onHand(productId));
    }
}
