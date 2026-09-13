package com.masspos.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param initialAdminPin PIN for the admin account created on first start. Left empty, a random one
 *                        is generated and written to the log. Either way it must be changed at the
 *                        first sign-in.
 */
@Validated
@ConfigurationProperties(prefix = "pos.auth")
public record AuthProperties(String initialAdminPin) {

    public AuthProperties {
        initialAdminPin = initialAdminPin == null ? "" : initialAdminPin.trim();
    }
}
