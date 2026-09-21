package com.masspos.license;

import com.masspos.auth.RequiresRole;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The licence screen, and the banner every screen shows while the licence needs attention. */
@RestController
@RequestMapping("/api/license")
public class LicenseController {

    private final LicenseService licenses;

    public LicenseController(LicenseService licenses) {
        this.licenses = licenses;
    }

    public record ActivateRequest(@NotBlank @Size(max = 4000) String key) {
    }

    /** Any signed-in user: a cashier needs to see that billing is about to stop. */
    @GetMapping
    public LicenseStatus status() {
        return licenses.status();
    }

    /** Installs a new or renewed licence key, as sent by the vendor. */
    @PostMapping
    @RequiresRole({UserRole.ADMIN})
    public LicenseStatus activate(@Valid @RequestBody ActivateRequest request) {
        return licenses.activate(request.key());
    }
}
