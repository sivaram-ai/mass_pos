package com.masspos;

import com.masspos.auth.AuthService;
import com.masspos.user.UserRole;

/** Signed-in staff for API tests, past the forced first PIN change. */
public final class TestAccounts {

    private static final String HANDOVER_PIN = "471102";
    private static final String CHOSEN_PIN = "883914";

    private TestAccounts() {
    }

    /** Creates a user, signs in, and completes the forced PIN change, returning a usable token. */
    public static String tokenFor(AuthService auth, String username, UserRole role) {
        auth.createUser(username, "Test " + role, role, HANDOVER_PIN);
        AuthService.LoginResult login = auth.signIn(username, HANDOVER_PIN);
        auth.changePin(login.principal().userId(), login.principal().tokenId(), HANDOVER_PIN, CHOSEN_PIN);
        return login.token();
    }
}
