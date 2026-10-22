package com.masspos.catalog;

import com.masspos.auth.RequiresRole;
import com.masspos.common.Csv;
import com.masspos.common.IndiaTime;
import com.masspos.inventory.InventoryService;
import com.masspos.user.UserRole;
import com.masspos.web.FieldRejectedException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The catalogue. Search is what the billing screen hits on every keystroke, so it stays cheap. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final int MAX_RESULTS = 25;

    private final ProductRepository products;
    private final InventoryService inventory;
    private final Validator validator;

    public ProductController(ProductRepository products, InventoryService inventory, Validator validator) {
        this.products = products;
        this.inventory = inventory;
        this.validator = validator;
    }

    /** @param added rows whose code was not already in the catalogue */
    public record ImportResult(int added, int updated) {
    }

    public record ProductView(UUID id, String sku, String barcode, String name, String hsnCode, UnitOfMeasure unit,
                              long sellingPricePaise, long mrpPaise, boolean taxInclusive, int gstRateBp,
                              int cessRateBp, boolean billWithoutStock, boolean active, long stockMilli) {

        static ProductView of(Product product, long stockMilli) {
            return new ProductView(product.getId(), product.getSku(), product.getBarcode(), product.getName(),
                    product.getHsnCode(), product.getUnit(), product.getSellingPricePaise(), product.getMrpPaise(),
                    product.isTaxInclusive(), product.getGstRateBp(), product.getCessRateBp(),
                    product.isBillWithoutStock(), product.isActive(), stockMilli);
        }
    }

    /**
     * @param hsnCode           optional; empty, or 4, 6 or 8 digits
     * @param sellingPricePaise 0 means the cashier types the rate on every bill
     * @param billWithoutStock  sell it even where the shop refuses to sell what it has run out of
     */
    public record SaveProductRequest(@NotBlank @Size(max = 40) String sku, @Size(max = 64) String barcode,
                                     @NotBlank @Size(max = 200) String name,
                                     @Pattern(regexp = Product.HSN_PATTERN, message = Product.HSN_MESSAGE)
                                     String hsnCode,
                                     @NotNull UnitOfMeasure unit,
                                     @PositiveOrZero long sellingPricePaise, @PositiveOrZero long mrpPaise,
                                     boolean taxInclusive,
                                     @Min(0) @Max(10_000) int gstRateBp, @Min(0) @Max(10_000) int cessRateBp,
                                     boolean billWithoutStock) {

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

    /**
     * The whole catalogue as a sheet. A shop with four hundred items would rather reprice them in
     * Excel than one dialog at a time, and this is the file the load button takes back.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<String> export() {
        List<Product> all = products.findAllByActiveTrueOrderByNameAsc();
        Map<UUID, Long> stock = inventory.onHandFor(all.stream().map(Product::getId).toList());
        String csv = ProductCsv.write(all.stream()
                .map(product -> ProductView.of(product, stock.getOrDefault(product.getId(), 0L)))
                .toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"products-" + LocalDate.now(IndiaTime.ZONE) + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(Csv.EXCEL_BOM + csv);
    }

    /**
     * Loads a sheet back. An item whose code is already in the catalogue is updated, one whose
     * code is new is added, and nothing is touched at all unless every row is good: a catalogue
     * half loaded, with row 40 refused, is worse to untangle than a refusal naming the rows.
     */
    @PostMapping(value = "/import", consumes = "text/csv")
    @RequiresRole({UserRole.MANAGER})
    @Transactional
    public ImportResult load(@RequestBody String csv) {
        List<SaveProductRequest> rows = ProductCsv.read(csv);
        List<String> problems = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            int line = index + 2; // the headings are row 1
            for (ConstraintViolation<SaveProductRequest> broken : validator.validate(rows.get(index))) {
                problems.add("row " + line + ": " + broken.getPropertyPath() + " " + broken.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw ProductCsv.refuse(problems);
        }
        int added = 0;
        for (SaveProductRequest row : rows) {
            added += apply(row) ? 1 : 0;
        }
        return new ImportResult(added, rows.size() - added);
    }

    /** @return true when the row added an item rather than changing one */
    private boolean apply(SaveProductRequest request) {
        Optional<Product> found = products.findBySkuIgnoreCase(request.sku().trim());
        checkPriceAndBarcode(found.map(Product::getId).orElse(null), request);
        Product product = found.orElseGet(() -> new Product(request.sku().trim(), request.name().trim(),
                request.hsnCode(), request.unit(), request.sellingPricePaise(), request.mrpPaise(),
                request.taxInclusive(), request.gstRateBp()));
        product.describe(request.name().trim(), request.hsnCode(), request.unit());
        product.reprice(request.sellingPricePaise(), request.mrpPaise());
        product.changeTax(request.gstRateBp(), request.cessRateBp(), request.taxInclusive());
        product.assignBarcode(request.barcodeOrNull());
        product.allowBillingWithoutStock(request.billWithoutStock());
        if (found.isEmpty()) {
            products.save(product);
            return true;
        }
        return false;
    }
    @PostMapping
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProductView create(@Valid @RequestBody SaveProductRequest request) {
        checkCode(null, request);
        checkPriceAndBarcode(null, request);
        Product product = new Product(request.sku().trim(), request.name().trim(), request.hsnCode(), request.unit(),
                request.sellingPricePaise(), request.mrpPaise(), request.taxInclusive(), request.gstRateBp());
        product.changeTax(request.gstRateBp(), request.cessRateBp(), request.taxInclusive());
        product.assignBarcode(request.barcodeOrNull());
        product.allowBillingWithoutStock(request.billWithoutStock());
        return ProductView.of(products.save(product), 0);
    }

    /**
     * Everything about the item, the code included: a code typed wrongly when it was added would
     * otherwise be stuck forever. Changes never touch invoices already issued, which carry their own
     * copies of the code, name and price.
     */
    @PutMapping("/{id}")
    @RequiresRole({UserRole.MANAGER})
    @Transactional
    public ProductView update(@PathVariable UUID id, @Valid @RequestBody SaveProductRequest request) {
        Product product = products.findById(id).orElseThrow(() -> new EntityNotFoundException("No such product"));
        checkCode(id, request);
        checkPriceAndBarcode(id, request);
        product.changeCode(request.sku().trim());
        product.describe(request.name().trim(), request.hsnCode(), request.unit());
        product.reprice(request.sellingPricePaise(), request.mrpPaise());
        product.changeTax(request.gstRateBp(), request.cessRateBp(), request.taxInclusive());
        product.assignBarcode(request.barcodeOrNull());
        product.allowBillingWithoutStock(request.billWithoutStock());
        return ProductView.of(product, inventory.onHand(id));
    }

    /** One code bills one item. {@code id} is the item being saved, which never clashes with itself. */
    private void checkCode(UUID id, SaveProductRequest request) {
        products.findBySkuIgnoreCase(request.sku().trim())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new FieldRejectedException("sku", "is already used by " + existing.getName());
                });
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
