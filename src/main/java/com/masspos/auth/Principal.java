package com.masspos.auth;

import com.masspos.user.UserRole;

import java.util.UUID;

/**
 * The signed-in person for the request being handled, and the counter they are standing at.
 *
 * @param counter which till this session bills as. The server's own code on the server's browser,
 *                and the machine's own code on a counter that connected over the LAN. Decided once
 *                at sign-in and kept on the token, so it cannot be changed by editing a request.
 */
public record Principal(UUID userId, String username, String displayName, UserRole role, UUID tokenId,
                        boolean mustChangePin, String counter) {
}
