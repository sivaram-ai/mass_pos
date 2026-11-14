package com.masspos.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.masspos.web.LocalApiGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /**
     * @param counterName what this machine is called on its own settings window, e.g. "Counter 2".
     *                    In the body rather than beside the counter number in a header, because it
     *                    is text somebody typed: a name in Devanagari or Tamil cannot go through a
     *                    header at all, and it is only ever acted on here.
     */
    /**
     * @param counterId which of the shop's counters this machine was told it is, from its own
     *                  settings window. Empty from the browser on the server, which bills as the
     *                  server. It is an id rather than a number because the number is the shop's
     *                  to set and to correct, and a machine that had kept a copy of it would go on
     *                  claiming to be a counter that no longer exists.
     */
    /**
     * @param takeOver true once somebody has been told their account is in use on another till and
     *                 has said to sign that one out and carry on here
     */
    public record LoginRequest(@NotBlank String username, @NotBlank String pin,
                               @Size(max = 36) String counterId, boolean takeOver) {
    }

    public record LoginResponse(String token, Principal user) {
    }

    public record ChangePinRequest(@NotBlank String currentPin, @NotBlank String newPin) {
    }

    /** The only public endpoint besides the UI files. */
    /**
     * The counter a machine claims comes in as a header rather than in the body: the browser adds
     * it to every request it makes, and sign-in is simply the one where it is acted on. Whether
     * the machine is on the network is read from the connection, never from what it says about
     * itself, so a counter cannot talk its way into being the server.
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.LoginResult result = auth.signIn(request.username(), request.pin(),
                request.counterId(), overTheNetwork(http), request.takeOver());
        return new LoginResponse(result.token(), result.principal());
    }

    /** Loopback means the browser is on the server machine itself. */
    private static boolean overTheNetwork(HttpServletRequest http) {
        try {
            return !InetAddress.getByName(http.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException unreadable) {
            // Cannot tell, so treat it as a client: the stricter of the two answers.
            return true;
        }
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
