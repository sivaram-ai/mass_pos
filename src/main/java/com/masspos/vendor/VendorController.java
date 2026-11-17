package com.masspos.vendor;

import com.masspos.auth.RequiresRole;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The people the shop buys from.
 *
 * <p>A manager's screen, with one exception that matters: **only an administrator may approve a
 * payment**. The person who hands money over is rarely the person who decides it should be handed
 * over, and a shop where those are the same person has no control at all.
 */
@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorService vendors;

    public VendorController(VendorService vendors) {
        this.vendors = vendors;
    }

    public record VendorRequest(@NotBlank @Size(max = 40) String code,
                                @NotBlank @Size(max = 120) String name,
                                @Size(max = 20) String phone,
                                @Size(max = 15) String gstin,
                                @Size(max = 200) String address,
                                @Size(max = 200) String note,
                                boolean active) {
    }

    public record PaymentRequest(@Positive long amountPaise,
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn,
                                 @NotNull VendorPaymentMode mode,
                                 @Size(max = 60) String reference,
                                 @Size(max = 200) String note) {
    }

    @GetMapping
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<VendorView> list() {
        return vendors.list();
    }

    /** Who they are, everything bought from them, everything paid, and what is left. */
    @GetMapping("/{id}")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public VendorProfile profile(@PathVariable UUID id) {
        return vendors.profile(id);
    }

    @PostMapping
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public VendorView create(@Valid @RequestBody VendorRequest request) {
        return vendors.create(request.code(), request.name(), request.phone(), request.gstin(),
                request.address(), request.note());
    }

    @PutMapping("/{id}")
    @RequiresRole({UserRole.MANAGER})
    public VendorView update(@PathVariable UUID id, @Valid @RequestBody VendorRequest request) {
        return vendors.update(id, request.code(), request.name(), request.phone(), request.gstin(),
                request.address(), request.note(), request.active());
    }

    @DeleteMapping("/{id}")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        vendors.delete(id);
    }

    /** Money handed over, which waits for an administrator before the balance moves. */
    @PostMapping("/{id}/payments")
    @RequiresRole({UserRole.MANAGER})
    @ResponseStatus(HttpStatus.CREATED)
    public VendorProfile.Payment pay(@PathVariable UUID id, @Valid @RequestBody PaymentRequest request) {
        return vendors.pay(id, request.amountPaise(), request.paidOn(), request.mode(), request.reference(),
                request.note());
    }

    /** Everything waiting on an administrator, oldest first. */
    @GetMapping("/payments/waiting")
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<VendorProfile.Payment> waiting() {
        return vendors.waitingForApproval();
    }

    /**
     * Agreeing to a payment. Administrators only - and {@code @RequiresRole} with no MANAGER in it
     * is exactly how that is said, because ADMIN always passes and nobody else here does.
     */
    @PostMapping("/payments/{paymentId}/approve")
    @RequiresRole({})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID paymentId) {
        vendors.approve(paymentId);
    }
}
