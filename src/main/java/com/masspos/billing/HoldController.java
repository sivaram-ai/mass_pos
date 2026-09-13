package com.masspos.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.auth.AuthContext;
import com.masspos.auth.RequiresRole;
import com.masspos.config.PosProperties;
import com.masspos.user.UserRepository;
import com.masspos.user.UserRole;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bills parked mid-sale. They take no invoice number and hold no stock: the customer who went back
 * for the milk has not bought anything yet.
 */
@RestController
@RequestMapping("/api/holds")
@RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
public class HoldController {

    private final HeldBillRepository holds;
    private final UserRepository users;
    private final PosProperties pos;
    private final ObjectMapper json;

    public HoldController(HeldBillRepository holds, UserRepository users, PosProperties pos, ObjectMapper json) {
        this.holds = holds;
        this.users = users;
        this.pos = pos;
        this.json = json;
    }

    public record HoldRequest(@NotBlank @Size(max = 40) String label, @NotNull JsonNode cart,
                              @PositiveOrZero long estimatedTotalPaise) {
    }

    public record HoldView(UUID id, String label, String cashier, Instant heldAt, long estimatedTotalPaise,
                           JsonNode cart) {
    }

    /** Newest first: the table that just asked to wait is the one the cashier looks for. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<HoldView> list() {
        return holds.findByTerminalCodeOrderByCreatedAtDesc(pos.terminal().code()).stream().map(this::viewOf).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public HoldView hold(@Valid @RequestBody HoldRequest request) {
        HeldBill held = new HeldBill(request.label(), pos.terminal().code(),
                users.getReferenceById(AuthContext.require().userId()), request.cart().toString(),
                request.estimatedTotalPaise());
        return viewOf(holds.save(held));
    }

    /** Resuming returns the cart and clears the hold, so the same bill cannot be taken twice. */
    @DeleteMapping("/{id}")
    @Transactional
    public HoldView resume(@PathVariable UUID id) {
        HeldBill held = holds.findById(id).orElseThrow(() -> new EntityNotFoundException("No held bill " + id));
        HoldView view = viewOf(held);
        holds.delete(held);
        return view;
    }

    private HoldView viewOf(HeldBill held) {
        try {
            return new HoldView(held.getId(), held.getLabel(), held.getCashier().getDisplayName(),
                    held.getCreatedAt(), held.getEstimatedTotalPaise(), json.readTree(held.getCartJson()));
        } catch (IOException e) {
            throw new UncheckedIOException("Held bill " + held.getId() + " has unreadable contents", e);
        }
    }
}
