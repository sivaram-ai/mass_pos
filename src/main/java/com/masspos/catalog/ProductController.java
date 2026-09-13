package com.masspos.catalog;

import com.masspos.auth.RequiresRole;
import com.masspos.inventory.InventoryService;
import com.masspos.user.UserRole;
import com.masspos.web.FieldRejectedException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The catalogue. Search is what the billing screen hits on every keystroke, so it stays cheap. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final int MAX_RESULTS = 25;

    private final ProductRepository products;
    private final InventoryService inventory;

    public ProductController(ProductRepository products, InventoryService inventory) {
        this.products = products;
        this.inventory = inventory;
    }

    public record ProductView(UUID id, String sku, String barcode, String name, String hsnCode, UnitOfMeasure unit,
                              long sellingPricePaise, long mrpPaise, boolean taxInclusive, int gstRateBp,
                              int cessRateBp, boolean active, long stockMilli) {

        static ProductView of(Product product, long stockMilli) {
            return new ProductView(product.getId(), product.getSku(), product.getBarcode(), product.getName(),
                    product.getHsnCode(), product.getUnit(), product.getSellingPricePaise(), product.getMrpPaise(),
                    product.isTaxInclusive(), product.getGstRateBp(), product.getCessRateBp(), product.isActive(),
                    stockMilli);
        }
    }

    /**
     * @param hsnCode           optional; empty, or 4, 6 or 8 digits
     * @param sellingPricePaise 0 means the cashier types the rate on every bill
     */
    public record SaveProductRequest(@NotBlank @Size(max = 40) String sku, @Size(max = 64) String barcode,
                                     @NotBlank @Size(max = 200) String name,
                                     @Pattern(regexp = Product.HSN_PATTERN, message = Product.HSN_MESSAGE)
                                     String hsnCode,
                                     @NotNull UnitOfMeasure unit,
                                     @PositiveOrZero long sellingPricePaise, @PositiveOrZero long mrpPaise,
                                     boolean taxInclusive,
                                     @Min(0) @Max(10_000) int gstRateBp, @Min(0) @Max(10_000) int cessRateBp) {

        String barcodeOrNull() {
            return barcode == null || barcode.isBlank() ? null : barcode.trim();
        }
    }

    /** Empty text lists the catalogue; anything else searches barcode, SKU, name and HSN. */
    @GetMapping
    public List<ProductView> search(@RequestParam(name = "q", required = false) String text) {
        List<Product> found = text == null || text.isBlank()
                ? products.findByActiveTrueOrderByNameAsc(Limit.of(MAX_RESULTS))
                : products.search(text.trim(), Limit.of(MAX_RESULTS));
        Map<UUID, Long> stock = inventory.onHandFor(found.stream().map(Product::getId).toList());
        return found.stream().map(product -> ProductView.of(product, stock.getOrDefault(product.getId(), 0L))).toList();
    }

    @GetMapping("/{id}")
    public ProductView byId(@PathVariable UUID id) {
        Product product = products.findById(id).orElseThrow(() -> new EntityNotFoundException("No such product"));
        return ProductView.of(product, inventory.onHand(id));
    }

    @PostMapping
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProductView create(@Valid @RequestBody SaveProductRequest request) {
        products.findBySkuIgnoreCase(request.sku().trim()).ifPresent(existing -> {
            throw new FieldRejectedException("sku", "is already used by " + existing.getName());
        });
        checkPriceAndBarcode(null, request);
        Product product = new Product(request.sku().trim(), request.name().trim(), request.hsnCode(), request.unit(),
                request.sellingPricePaise(), request.mrpPaise(), request.taxInclusive(), request.gstRateBp());
        product.changeTax(request.gstRateBp(), request.cessRateBp(), request.taxInclusive());
        product.assignBarcode(request.barcodeOrNull());
        return ProductView.of(products.save(product), 0);
    }

    /**
     * Everything but the item code, which bills and stock movements refer to. Changes never touch
     * invoices already issued: those carry their own copies.
     */
    @PutMapping("/{id}")
    @RequiresRole({UserRole.MANAGER})
    @Transactional
    public ProductView update(@PathVariable UUID id, @Valid @RequestBody SaveProductRequest request) {
        Product product = products.findById(id).orElseThrow(() -> new EntityNotFoundException("No such product"));
        checkPriceAndBarcode(id, request);
        product.describe(request.name().trim(), request.hsnCode(), request.unit());
        product.reprice(request.sellingPricePaise(), request.mrpPaise());
        product.changeTax(request.gstRateBp(), request.cessRateBp(), request.taxInclusive());
        product.assignBarcode(request.barcodeOrNull());
        return ProductView.of(product, inventory.onHand(id));
    }

    /** Checked here so the screen can mark the field, rather than failing at commit. */
    private void checkPriceAndBarcode(UUID id, SaveProductRequest request) {
        if (request.mrpPaise() > 0 && request.sellingPricePaise() > request.mrpPaise()) {
            throw new FieldRejectedException("sellingPricePaise", "must not be more than the MRP");
        }
        String barcode = request.barcodeOrNull();
        if (barcode != null) {
            products.findByBarcode(barcode)
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new FieldRejectedException("barcode", "is already used by " + existing.getName());
                    });
        }
    }

    @PostMapping("/{id}/deactivate")
    @RequiresRole({UserRole.MANAGER})
    @Transactional
    public ProductView deactivate(@PathVariable UUID id) {
        Product product = products.findById(id).orElseThrow(() -> new EntityNotFoundException("No such product"));
        product.deactivate();
        return ProductView.of(product, inventory.onHand(id));
    }
}
