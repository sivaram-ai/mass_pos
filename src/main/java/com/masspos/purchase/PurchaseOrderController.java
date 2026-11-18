package com.masspos.purchase;

import com.masspos.auth.RequiresRole;
import com.masspos.common.IndiaTime;
import com.masspos.user.UserRole;
import com.masspos.vendor.VendorPaymentMode;
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
    private final PurchasePrices prices;

    public PurchaseOrderController(PurchaseOrderService purchases, PurchasePrices prices) {
        this.purchases = purchases;
        this.prices = prices;
    }

    /**
     * @param itemName where neither id is given: a one-off the shop does not keep a count of
     */
    public record LineRequest(UUID productId, UUID rawMaterialId,
                              @Size(max = 200) String itemName, @Size(max = 40) String itemCode,
                              long quantityMilli, long unitCostPaise) {
    }

    /** Money handed over as the order is written. */
    public record PaymentRequest(@NotNull VendorPaymentMode mode, long amountPaise,
                                 @Size(max = 60) String reference) {
    }

    /**
     * @param vendorId   somebody on the shop's list, or null for a vendor it does not keep
     * @param vendorName who it is going to where there is no vendor on the list. Optional too: an
     *                   order with nobody named is a shop that has not decided yet.
     */
    public record OrderRequest(UUID vendorId,
                               @Size(max = 120) String vendorName,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedOn,
                               @Size(max = 200) String note,
                               @NotNull List<LineRequest> lines,
                               List<PaymentRequest> payments) {
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

    /**
     * What the shop has paid for one thing over the years, every delivery of it with the vendor,
     * the quantity, the price each, the order and who agreed to it.
     *
     * <p>Asked of an item or of a raw material, because the question is the same either way: are
     * we paying more than we were, and is this man dearer than that one.
     */
    @GetMapping("/prices")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<PurchasePrices.PaidPrice> prices(@RequestParam(required = false) UUID productId,
                                                 @RequestParam(required = false) UUID rawMaterialId) {
        if (productId != null) {
            return prices.forProduct(productId);
        }
        if (rawMaterialId != null) {
            return prices.forMaterial(rawMaterialId);
        }
        throw new IllegalArgumentException("Choose an item first, then ask what it has cost.");
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
        return purchases.create(request.vendorId(), request.vendorName(), request.expectedOn(),
                request.note(), wanted(request), paidNow(request));
    }

    /**
     * Changing an order: this one is cancelled and a new one takes its place, the way an edited
     * bill is reissued. Only before the goods come in - after that the counts have moved, and
     * what should not have come goes back rather than being edited away.
     */
    @PostMapping("/{id}/replace")
    @RequiresRole({UserRole.MANAGER})
    public PurchaseOrderView replace(@PathVariable UUID id, @Valid @RequestBody OrderRequest request) {
        return purchases.replace(id, request.vendorId(), request.vendorName(), request.expectedOn(),
                request.note(), wanted(request), paidNow(request));
    }

    /** Every version of one order, and whatever went back against any of them. */
    @GetMapping("/{id}/history")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public PurchaseOrderService.Story history(@PathVariable UUID id) {
        return purchases.history(id);
    }

    private static List<PurchaseOrderService.Wanted> wanted(OrderRequest request) {
        return request.lines().stream()
                .map(line -> new PurchaseOrderService.Wanted(line.productId(), line.rawMaterialId(),
                        line.itemName(), line.itemCode(), line.quantityMilli(), line.unitCostPaise()))
                .toList();
    }

    private static List<PurchaseOrderService.PaidNow> paidNow(OrderRequest request) {
        return (request.payments() == null ? List.<PaymentRequest>of() : request.payments()).stream()
                .map(paid -> new PurchaseOrderService.PaidNow(paid.mode(), paid.amountPaise(), paid.reference()))
                .toList();
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
