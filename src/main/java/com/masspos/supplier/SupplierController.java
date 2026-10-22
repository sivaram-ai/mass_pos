package com.masspos.supplier;

import com.masspos.auth.RequiresRole;
import com.masspos.user.UserRole;
import com.masspos.web.FieldRejectedException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import java.util.UUID;

/**
 * Suppliers: who a bill is against. Added on the Staff screen beside the people, picked on the
 * billing screen when the shop asks for one.
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierRepository suppliers;

    public SupplierController(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    /**
     * @param supplierId what goes on the bill
     */
    public record SupplierView(UUID id, String supplierId, String supplierName, boolean active) {

        static SupplierView of(Supplier supplier) {
            return new SupplierView(supplier.getId(), supplier.getCode(), supplier.getName(), supplier.isActive());
        }
    }

    public record SaveSupplierRequest(@NotBlank @Size(max = 40) String supplierId,
                                      @NotBlank @Size(max = 100) String supplierName) {
    }

    /**
     * A cashier needs this to pick one at the start of a bill, so it is open wider than the staff
     * roster. Nothing here is a secret: both fields are printed on the bill.
     *
     * @param all true on the Staff screen, which shows the ones no longer used as well
     */
    @GetMapping
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER, UserRole.AUDITOR})
    @Transactional(readOnly = true)
    public List<SupplierView> list(@RequestParam(defaultValue = "false") boolean all) {
        List<Supplier> found = all ? suppliers.findAllByOrderByNameAsc() : suppliers.findByActiveTrueOrderByNameAsc();
        return found.stream().map(SupplierView::of).toList();
    }

    @PostMapping
    @RequiresRole({UserRole.ADMIN})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SupplierView create(@Valid @RequestBody SaveSupplierRequest request) {
        checkCode(null, request.supplierId());
        return SupplierView.of(suppliers.save(new Supplier(request.supplierId(), request.supplierName())));
    }

    @PutMapping("/{id}")
    @RequiresRole({UserRole.ADMIN})
    @Transactional
    public SupplierView update(@PathVariable UUID id, @Valid @RequestBody SaveSupplierRequest request) {
        Supplier supplier = suppliers.findById(id).orElseThrow(() -> new EntityNotFoundException("No such supplier"));
        checkCode(id, request.supplierId());
        supplier.rename(request.supplierId(), request.supplierName());
        return SupplierView.of(supplier);
    }

    /** Stopped rather than deleted: bills already issued carry the code and must still read. */
    @PostMapping("/{id}/deactivate")
    @RequiresRole({UserRole.ADMIN})
    @Transactional
    public SupplierView deactivate(@PathVariable UUID id) {
        Supplier supplier = suppliers.findById(id).orElseThrow(() -> new EntityNotFoundException("No such supplier"));
        supplier.deactivate();
        return SupplierView.of(supplier);
    }

    @PostMapping("/{id}/activate")
    @RequiresRole({UserRole.ADMIN})
    @Transactional
    public SupplierView activate(@PathVariable UUID id) {
        Supplier supplier = suppliers.findById(id).orElseThrow(() -> new EntityNotFoundException("No such supplier"));
        supplier.activate();
        return SupplierView.of(supplier);
    }

    /** One ID belongs to one supplier; the one being saved never clashes with itself. */
    private void checkCode(UUID id, String code) {
        suppliers.findByCodeIgnoreCase(code.trim())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new FieldRejectedException("supplierId", "is already used by " + existing.getName());
                });
    }
}