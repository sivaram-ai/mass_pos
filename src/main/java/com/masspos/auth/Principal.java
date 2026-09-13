package com.masspos.auth;

import com.masspos.user.UserRole;

import java.util.UUID;

/** The signed-in person for the request being handled. */
public record Principal(UUID userId, String username, String displayName, UserRole role, UUID tokenId,
                        boolean mustChangePin) {
}
