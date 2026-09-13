package com.masspos.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String pin) {
    }

    public record LoginResponse(String token, Principal user) {
    }

    public record ChangePinRequest(@NotBlank String currentPin, @NotBlank String newPin) {
    }

    /** The only public endpoint besides the UI files. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = auth.signIn(request.username(), request.pin());
        return new LoginResponse(result.token(), result.principal());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        auth.signOut(AuthContext.require().tokenId());
    }

    @GetMapping("/me")
    public Principal me() {
        return AuthContext.require();
    }

    /** Also clears the forced change flag set on a new or reset account. */
    @PostMapping("/change-pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePin(@Valid @RequestBody ChangePinRequest request) {
        Principal me = AuthContext.require();
        auth.changePin(me.userId(), me.tokenId(), request.currentPin(), request.newPin());
    }
}
