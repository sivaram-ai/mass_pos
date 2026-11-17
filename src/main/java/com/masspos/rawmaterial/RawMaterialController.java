package com.masspos.rawmaterial;

import com.masspos.auth.RequiresRole;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The store behind the kitchen.
 *
 * <p>A manager's screen throughout: nothing here is part of taking a bill, and a cashier has no
 * reason to be recording what the kitchen used. Auditors may read it, as they may read stock.
 */
@RestController
@RequestMapping("/api/raw-materials")
public class RawMaterialController {

    private final RawMaterialService store;

    public RawMaterialController(RawMaterialService store) {
        this.store = store;
    }

    /**
     * @param reorderLevelMilli tell me before it runs below this; 0 means nobody is watching it
     */
    public record MaterialRequest(@NotBlank @Size(max = 40) String code,
                                  @NotBlank @Size(max = 200) String name,
                                  @NotNull UnitOfMeasure unit,
                                  @PositiveOrZero long reorderLevelMilli,
                                  boolean active) {
    }

    /** Something leaving the store: used, wasted or sent back. */
    public record TakeOutRequest(@Positive long quantityMilli, @Size(max = 200) String note) {
    }

    public record PurchaseRequest(@Positive long quantityMilli, @Size(max = 200) String note,
                                  @PositiveOrZero long buyingPricePaise,
                                  @Size(max = 120) String vendorName,
                                  UUID vendorId) {
    }

    public record CorrectionRequest(long deltaMilli, @Size(max = 200) String note) {
    }

    @GetMapping
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<RawMaterialView> list() {
        return store.list();
    }

    @PostMapping
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public RawMaterialView create(@Valid @RequestBody MaterialRequest request) {
        return store.create(request.code(), request.name(), request.unit(), request.reorderLevelMilli());
    }

    @PutMapping("/{id}")
    @RequiresRole({UserRole.MANAGER})
    public RawMaterialView update(@PathVariable UUID id, @Valid @RequestBody MaterialRequest request) {
        return store.update(id, request.code(), request.name(), request.unit(), request.reorderLevelMilli(),
                request.active());
    }

    @DeleteMapping("/{id}")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        store.delete(id);
    }

    @PostMapping("/{id}/purchases")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public RawMaterialView purchase(@PathVariable UUID id, @Valid @RequestBody PurchaseRequest request) {
        return store.purchase(id, request.quantityMilli(), request.note(), request.buyingPricePaise(),
                request.vendorName(), request.vendorId());
    }

    @PostMapping("/{id}/consumption")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public RawMaterialView consume(@PathVariable UUID id, @Valid @RequestBody TakeOutRequest request) {
        return store.consume(id, request.quantityMilli(), request.note());
    }

    @PostMapping("/{id}/wastage")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public RawMaterialView waste(@PathVariable UUID id, @Valid @RequestBody TakeOutRequest request) {
        return store.waste(id, request.quantityMilli(), request.note());
    }

    @PostMapping("/{id}/vendor-returns")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public RawMaterialView returnToVendor(@PathVariable UUID id, @Valid @RequestBody TakeOutRequest request) {
        return store.returnToVendor(id, request.quantityMilli(), request.note());
    }

    @PostMapping("/{id}/corrections")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public RawMaterialView correct(@PathVariable UUID id, @Valid @RequestBody CorrectionRequest request) {
        return store.adjust(id, request.deltaMilli(), request.note());
    }

    /** One material's whole history, or every material's movements over the last {@code days}. */
    @GetMapping("/history")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<RawMaterialMovementView> history(@RequestParam(name = "materialId", required = false) UUID materialId,
                                                 @RequestParam(defaultValue = "30") int days) {
        if (materialId != null) {
            return store.historyOf(materialId);
        }
        return store.historySince(Instant.now().minus(Duration.ofDays(Math.max(1, Math.min(days, 370)))));
    }

    @GetMapping("/vendors")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<String> vendors() {
        return store.vendorsSoFar();
    }
}
