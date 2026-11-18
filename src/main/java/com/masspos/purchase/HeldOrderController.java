package com.masspos.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masspos.auth.AuthContext;
import com.masspos.auth.RequiresRole;
import com.masspos.client.CurrentCounter;
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
 * Orders put aside half written, worked exactly as held bills are - because it is the same
 * interruption: somebody has to go and look at the shelf, or the vendor rings back, and what is on
 * the screen must not be the price of answering the telephone.
 *
 * <p>Held on the counter that wrote it, like a bill: the order is one person's half-finished work,
 * not the shop's, until it is written.
 */
@RestController
@RequestMapping("/api/purchase-orders/holds")
@RequiresRole({UserRole.MANAGER})
public class HeldOrderController {

    private final HeldPurchaseOrderRepository holds;
    private final UserRepository users;
    private final CurrentCounter counter;
    private final ObjectMapper json;

    public HeldOrderController(HeldPurchaseOrderRepository holds, UserRepository users, CurrentCounter counter,
                               ObjectMapper json) {
        this.holds = holds;
        this.users = users;
        this.counter = counter;
        this.json = json;
    }

    public record HoldRequest(@NotBlank @Size(max = 40) String label, @NotNull JsonNode order,
                              @PositiveOrZero long estimatedTotalPaise) {
    }

    public record HoldView(UUID id, String label, String by, Instant heldAt, long estimatedTotalPaise,
                           JsonNode order) {
    }

    /** Newest first: the one just put aside is the one somebody is looking for. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<HoldView> list() {
        return holds.heldOn(counter.code()).stream().map(this::viewOf).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public HoldView hold(@Valid @RequestBody HoldRequest request) {
        HeldPurchaseOrder held = new HeldPurchaseOrder(request.label(), counter.code(),
                users.getReferenceById(AuthContext.require().userId()), request.order().toString(),
                request.estimatedTotalPaise());
        return viewOf(holds.save(held));
    }

    /** Resuming hands the order back and clears the hold, so it cannot be written twice. */
    @DeleteMapping("/{id}")
    @Transactional
    public HoldView resume(@PathVariable UUID id) {
        HeldPurchaseOrder held = holds.findById(id).orElseThrow(() -> new EntityNotFoundException(
                "That held order is not there any more. Refresh the screen to see the ones that are."));
        HoldView view = viewOf(held);
        holds.delete(held);
        return view;
    }

    private HoldView viewOf(HeldPurchaseOrder held) {
        try {
            return new HoldView(held.getId(), held.getLabel(), held.getWrittenBy().getDisplayName(),
                    held.getCreatedAt(), held.getEstimatedTotalPaise(), json.readTree(held.getOrderJson()));
        } catch (IOException e) {
            throw new UncheckedIOException("Held order " + held.getId() + " has unreadable contents", e);
        }
    }
}
