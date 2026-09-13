package com.masspos.auth;

import com.masspos.config.PosProperties;
import com.masspos.user.User;
import com.masspos.user.UserRepository;
import com.masspos.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Sign-in, sign-out and account maintenance. Tokens never expire: a cashier signs in at the start
 * of a shift and stays signed in until Log out is pressed, or until an admin resets their PIN.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int TOKEN_BYTES = 32;

    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final PosProperties pos;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, AuthTokenRepository tokens, PosProperties pos) {
        this.users = users;
        this.tokens = tokens;
        this.pos = pos;
    }

    public record LoginResult(String token, Principal principal) {
    }

    @Transactional
    public LoginResult signIn(String username, String pin) {
        User user = users.findByUsernameIgnoreCase(username == null ? "" : username.trim())
                .filter(User::isActive)
                .orElseThrow(() -> new AuthException("Wrong username or PIN"));
        if (!encoder.matches(pin == null ? "" : pin, user.getPinHash())) {
            log.warn("Failed sign-in for {} on {}", user.getUsername(), pos.terminal().code());
            throw new AuthException("Wrong username or PIN");
        }
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        AuthToken saved = tokens.save(new AuthToken(user, hash(token), pos.terminal().code()));
        log.info("{} signed in on {}", user.getUsername(), pos.terminal().code());
        return new LoginResult(token, principal(user, saved.getId()));
    }

    @Transactional(readOnly = true)
    public Optional<Principal> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHashAndRevokedAtIsNull(hash(token))
                .filter(authToken -> authToken.getUser().isActive())
                .map(authToken -> principal(authToken.getUser(), authToken.getId()));
    }

    @Transactional
    public void signOut(UUID tokenId) {
        tokens.findById(tokenId).ifPresent(token -> token.revoke("LOGOUT", Instant.now()));
    }

    /**
     * Changing a PIN signs that user out of every other till, in case they left one signed in. The
     * till they are standing at stays signed in: nobody wants to be thrown out mid-queue.
     */
    @Transactional
    public void changePin(UUID userId, UUID keepTokenId, String currentPin, String newPin) {
        User user = users.findById(userId).orElseThrow(() -> new AuthException("Not signed in"));
        if (!encoder.matches(currentPin == null ? "" : currentPin, user.getPinHash())) {
            throw new AuthException("Current PIN is wrong");
        }
        Pins.check(newPin);
        if (newPin.equals(currentPin)) {
            throw new IllegalArgumentException("The new PIN must be different");
        }
        user.changePinHash(encoder.encode(newPin));
        user.pinChanged();
        Instant now = Instant.now();
        tokens.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(token -> !token.getId().equals(keepTokenId))
                .forEach(token -> token.revoke("PIN_CHANGED", now));
    }

    @Transactional
    public User createUser(String username, String displayName, UserRole role, String pin) {
        String name = username == null ? "" : username.trim();
        if (!name.matches("[A-Za-z0-9._-]{3,50}")) {
            throw new IllegalArgumentException("Username must be 3-50 letters, digits, dot, dash or underscore");
        }
        if (users.findByUsernameIgnoreCase(name).isPresent()) {
            throw new IllegalArgumentException("A user named " + name + " already exists");
        }
        Pins.check(pin);
        User user = new User(name, displayName, encoder.encode(pin), role);
        user.requirePinChange();
        return users.save(user);
    }

    /**
     * Renaming, changing a role, deactivating. Deactivating signs the person out of every till at
     * once, which is the point when someone leaves mid-shift.
     */
    @Transactional
    public User updateUser(UUID userId, String displayName, UserRole role, boolean active) {
        User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("No such user"));
        boolean wouldLoseAdmin = user.getRole() == UserRole.ADMIN && user.isActive()
                && (role != UserRole.ADMIN || !active);
        if (wouldLoseAdmin && users.countByRoleAndActiveTrue(UserRole.ADMIN) <= 1) {
            throw new IllegalArgumentException(
                    "This is the only administrator; give someone else the admin role first");
        }
        user.rename(displayName);
        user.changeRole(role);
        if (active) {
            user.activate();
        } else {
            user.deactivate();
            revokeAllFor(userId, "DEACTIVATED");
        }
        return user;
    }

    /** Admin reset: the user picks their own PIN at the next sign-in. */
    @Transactional
    public void resetPin(UUID userId, String newPin) {
        Pins.check(newPin);
        User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("No such user"));
        user.changePinHash(encoder.encode(newPin));
        user.requirePinChange();
        revokeAllFor(userId, "PIN_RESET");
    }

    @Transactional
    public void revokeAllFor(UUID userId, String reason) {
        Instant now = Instant.now();
        tokens.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(reason, now));
    }

    String encodePin(String pin) {
        return encoder.encode(pin);
    }

    private Principal principal(User user, UUID tokenId) {
        return new Principal(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), tokenId,
                user.isMustChangePin());
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
