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


/**
 * A shop with no admin could never sign in, so the first start creates one.
 *
 * <p>The PIN is <strong>0000</strong> unless {@code pos.auth.initial-admin-pin} says otherwise. It
 * used to be random and written to the log, which meant whoever set the till up had to find
 * pos.log before they could get in - on a machine where the log is inside the installed folder and
 * the person is standing at a counter. A known PIN that the very next screen forces them to change
 * is the better trade: there is nothing in the till yet to protect.
 */
@Component
@Order(50)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String USERNAME = "admin";
    /** Printed in the setup guide. The first sign-in refuses to go anywhere until it is changed. */
    static final String FIRST_PIN = "0000";

    private final UserRepository users;
    private final AuthService auth;
    private final AuthProperties settings;

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
        String pin = configured ? settings.initialAdminPin() : FIRST_PIN;
        auth.createFirstAdmin(USERNAME, "Administrator", pin);

        log.warn("""

                ***************************************************************
                  First start: an administrator account has been created.
                    username: {}
                    PIN:      {}
                  Sign in and change this PIN straight away.
                ***************************************************************
                """, USERNAME, configured ? "(the one set in pos.auth.initial-admin-pin)" : pin);
    }
}
