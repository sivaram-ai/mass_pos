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
 * Goods going back to the vendor: a manager's job, like writing an order and taking a delivery in.
 *
 * <p>No administrator stands between the shop and sending a bad sack back, deliberately. Agreeing
 * to spend money is a decision somebody should make twice; handing back what should never have
 * come is not, and making a manager wait for an approval to put a crate on the lorry that brought
 * it is how shops stop recording returns at all.
 */
@RestController
@RequestMapping("/api/purchase-returns")
public class PurchaseReturnController {

    private final PurchaseReturnService returns;

    public PurchaseReturnController(PurchaseReturnService returns) {
        this.returns = returns;
    }

    public record LineRequest(UUID productId, UUID rawMaterialId,
                              @Size(max = 200) String itemName, @Size(max = 40) String itemCode,
                              long quantityMilli, long unitCostPaise) {
    }

    public record ReturnRequest(UUID vendorId, @Size(max = 120) String vendorName, UUID againstOrderId,
                                @Size(max = 200) String reason,
                                @NotNull List<LineRequest> lines) {
    }

    /** Returns in a window, newest first. No dates means the last ninety days. */
    @GetMapping
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<PurchaseReturnView> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID vendorId) {
        LocalDate today = LocalDate.now(IndiaTime.ZONE);
        return returns.search(from == null ? today.minusDays(90) : from, to == null ? today : to, vendorId);
    }

    /** What can still go back off an order, which is what fills the return in. */
    @GetMapping("/can-go-back")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<PurchaseReturnView.CanGoBack> canGoBack(@RequestParam UUID orderId) {
        return returns.whatCanGoBack(orderId);
    }

    @GetMapping("/{id}")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public PurchaseReturnView one(@PathVariable UUID id) {
        return returns.one(id);
    }

    @PostMapping
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseReturnView send(@Valid @RequestBody ReturnRequest request) {
        return returns.send(request.vendorId(), request.vendorName(), request.againstOrderId(),
                request.reason(),
                request.lines().stream()
                        .map(line -> new PurchaseReturnService.Going(line.productId(), line.rawMaterialId(),
                                line.itemName(), line.itemCode(), line.quantityMilli(), line.unitCostPaise()))
                        .toList());
    }
}
