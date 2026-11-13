package com.masspos.terminal;

import com.masspos.auth.RequiresRole;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The shop's counters, as an admin keeps them. Reading the list is {@code /api/settings/tills},
 * beside the rest of what that screen shows; this is only the changing of it.
 */
@RestController
@RequestMapping("/api/terminals")
@RequiresRole({UserRole.ADMIN})
public class TerminalController {

    private final TerminalAdmin counters;

    public TerminalController(TerminalAdmin counters) {
        this.counters = counters;
    }

    /** @param code fixed once the counter exists; it is in every bill number that till issues */
    public record NewCounterRequest(@NotBlank @Size(max = 3) String code, @NotBlank @Size(max = 60) String name,
                                    @Size(max = 60) String section) {
    }

    public record RenameRequest(@NotBlank @Size(max = 60) String name, @Size(max = 60) String section) {
    }

    public record BlockRequest(boolean blocked) {
    }

    /** @return the counter, whose id is what the machine at that counter will be told to be */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Added create(@Valid @RequestBody NewCounterRequest request) {
        Terminal made = counters.create(
                new TerminalAdmin.NewCounter(request.code(), request.name(), request.section()));
        return new Added(made.getId().toString(), made.getCode(), made.getName());
    }

    public record Added(String id, String code, String name) {
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        counters.rename(id, request.name(), request.section());
    }

    @PutMapping("/{id}/blocked")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@PathVariable UUID id, @RequestBody BlockRequest request) {
        counters.block(id, request.blocked());
    }
}
