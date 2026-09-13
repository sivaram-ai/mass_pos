package com.masspos.auth;

import com.masspos.user.User;
import com.masspos.user.UserRepository;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Staff accounts: cashiers, managers, auditors and admins. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;
    private final AuthService auth;

    public UserController(UserRepository users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    public record UserSummary(UUID id, String username, String displayName, UserRole role, boolean active,
                              boolean mustChangePin) {

        static UserSummary of(User user) {
            return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                    user.isActive(), user.isMustChangePin());
        }
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank @Size(max = 100) String displayName,
                                    @NotNull UserRole role, @NotBlank String pin) {
    }

    public record UpdateUserRequest(@NotBlank @Size(max = 100) String displayName, @NotNull UserRole role,
                                    boolean active) {
    }

    public record ResetPinRequest(@NotBlank String newPin) {
    }

    /** Managers see the roster to know who is on shift; only an admin may change it. */
    @GetMapping
    @RequiresRole({UserRole.MANAGER, UserRole.AUDITOR})
    public List<UserSummary> list() {
        return users.findAllByOrderByDisplayNameAsc().stream().map(UserSummary::of).toList();
    }

    @PostMapping
    @RequiresRole({UserRole.ADMIN})
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummary create(@Valid @RequestBody CreateUserRequest request) {
        return UserSummary.of(auth.createUser(request.username(), request.displayName(), request.role(), request.pin()));
    }

    @PutMapping("/{id}")
    @RequiresRole({UserRole.ADMIN})
    public UserSummary update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return UserSummary.of(auth.updateUser(id, request.displayName(), request.role(), request.active()));
    }

    @PostMapping("/{id}/pin")
    @RequiresRole({UserRole.ADMIN})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPin(@PathVariable UUID id, @Valid @RequestBody ResetPinRequest request) {
        auth.resetPin(id, request.newPin());
    }
}
