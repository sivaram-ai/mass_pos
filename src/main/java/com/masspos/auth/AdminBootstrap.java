package com.masspos.auth;

import com.masspos.user.UserRepository;
import com.masspos.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * A shop with no admin could never sign in, so the first start creates one. The PIN is random
 * unless {@code pos.auth.initial-admin-pin} is set, is written to the log once, and must be
 * changed at the first sign-in.
 */
@Component
@Order(50)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String USERNAME = "admin";

    private final UserRepository users;
    private final AuthService auth;
    private final AuthProperties settings;
    private final SecureRandom random = new SecureRandom();

    public AdminBootstrap(UserRepository users, AuthService auth, AuthProperties settings) {
        this.users = users;
        this.auth = auth;
        this.settings = settings;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRoleAndActiveTrue(UserRole.ADMIN)) {
            return;
        }
        boolean configured = !settings.initialAdminPin().isEmpty();
        String pin = configured ? settings.initialAdminPin() : randomPin();
        auth.createUser(USERNAME, "Administrator", UserRole.ADMIN, pin);

        log.warn("""

                ***************************************************************
                  First start: an administrator account has been created.
                    username: {}
                    PIN:      {}
                  Sign in and change this PIN. It is not shown again.
                ***************************************************************
                """, USERNAME, configured ? "(the one set in pos.auth.initial-admin-pin)" : pin);
    }

    private String randomPin() {
        while (true) {
            String pin = "%06d".formatted(random.nextInt(1_000_000));
            try {
                Pins.check(pin);
                return pin;
            } catch (IllegalArgumentException tooEasy) {
                // Draw again: the generator can produce 111111 like any other number.
            }
        }
    }
}
