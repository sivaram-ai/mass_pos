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

    public record StockRow(UUID productId, String sku, String name, UnitOfMeasure unit, long stockMilli,
                           long sellingPricePaise, long stockValuePaise) {
    }

    public record MovementRequest(@NotNull UUID productId, @Positive long quantityMilli,
                                  @Size(max = 200) String note) {
    }

    public record AdjustmentRequest(@NotNull UUID productId, long deltaMilli, @Size(max = 200) String note) {
    }

    /** Stock on hand for every product, valued at the current selling price. */
    @GetMapping
    public List<StockRow> stock() {
        Map<UUID, Long> onHand = inventory.onHandForAll();
        return products.findAll().stream()
                .filter(Product::isActive)
                .map(product -> {
                    long quantity = onHand.getOrDefault(product.getId(), 0L);
                    return new StockRow(product.getId(), product.getSku(), product.getName(), product.getUnit(),
                            quantity, product.getSellingPricePaise(),
                            Math.round(quantity * product.getSellingPricePaise() / 1000.0));
                })
                .sorted(Comparator.comparing(StockRow::name))
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
        long quantity = inventory.onHand(productId);
        return new StockRow(productId, product.getSku(), product.getName(), product.getUnit(), quantity,
                product.getSellingPricePaise(), Math.round(quantity * product.getSellingPricePaise() / 1000.0));
    }
}
