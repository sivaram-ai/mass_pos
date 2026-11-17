package com.masspos.purchase;

import com.masspos.auth.RequiresRole;
import com.masspos.common.IndiaTime;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ordering goods.
 *
 * <p>A manager writes orders and takes deliveries in; **only an administrator approves or rejects
 * one**, which is the whole reason the order exists as a separate thing from the delivery.
 * {@code @RequiresRole({})} is how that is said: ADMIN short-circuits the interceptor and an empty
 * list contains nobody else.
 */
@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchases;

    public PurchaseOrderController(PurchaseOrderService purchases) {
        this.purchases = purchases;
    }

    public record LineRequest(UUID productId, UUID rawMaterialId, long quantityMilli, long unitCostPaise) {
    }

    public record OrderRequest(@NotNull UUID vendorId,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedOn,
                               @Size(max = 200) String note,
                               @NotNull List<LineRequest> lines) {
    }

    public record DecisionRequest(@Size(max = 200) String decision) {
    }

    public record ArrivalRequest(@NotNull UUID lineId, long quantityMilli, long unitCostPaise) {
    }

    public record ReceiveRequest(List<ArrivalRequest> arrived) {
    }

    /**
     * Orders in a window, newest first. Everything is optional: no dates means the last ninety
     * days, which is what somebody opening the screen wants to see.
     */
    @GetMapping
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<PurchaseOrderView> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) PurchaseOrderStatus status) {
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        return purchases.search(from == null ? today.minusDays(90) : from, to == null ? today : to,
                vendorId, status);
    }

    @GetMapping("/waiting")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<PurchaseOrderView> waiting() {
        return purchases.waitingForApproval();
    }

    @GetMapping("/{id}")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public PurchaseOrderView one(@PathVariable UUID id) {
        return purchases.one(id);
    }

    @PostMapping
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderView create(@Valid @RequestBody OrderRequest request) {
        return purchases.create(request.vendorId(), request.expectedOn(), request.note(),
                request.lines().stream()
                        .map(line -> new PurchaseOrderService.Wanted(line.productId(), line.rawMaterialId(),
                                line.quantityMilli(), line.unitCostPaise()))
                        .toList());
    }

    /** Administrators only: this is the shop agreeing to spend the money. */
    @PostMapping("/{id}/approve")
    @RequiresRole({})
    public PurchaseOrderView approve(@PathVariable UUID id) {
        return purchases.approve(id);
    }

    @PostMapping("/{id}/reject")
    @RequiresRole({})
    public PurchaseOrderView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return purchases.reject(id, request.decision());
    }

    @PostMapping("/{id}/cancel")
    @RequiresRole({UserRole.MANAGER})
    public PurchaseOrderView cancel(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return purchases.cancel(id, request.decision());
    }

    /** The goods turn up, and only now do the counts move. */
    @PostMapping("/{id}/receive")
    @RequiresRole({UserRole.MANAGER})
    public PurchaseOrderView receive(@PathVariable UUID id, @RequestBody(required = false) ReceiveRequest request) {
        return purchases.receive(id, request == null || request.arrived() == null ? List.of()
                : request.arrived().stream()
                        .map(one -> new PurchaseOrderService.Arrived(one.lineId(), one.quantityMilli(),
                                one.unitCostPaise()))
                        .toList());
    }
}
