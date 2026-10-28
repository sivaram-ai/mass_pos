package com.masspos.client;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How many billing machines may connect to this one, and how they find it.
 *
 * <p>{@code max} is the whole switch. Left at 0 the till is what it has always been: a single
 * machine, listening on loopback only, reachable by nothing on the network. Set above 0 and this
 * machine becomes the shop's server - it binds the LAN, answers discovery, and lets that many
 * other machines bill against its database. The till running on the server itself never counts
 * against the number, because it reaches the server over loopback and needs no permission.
 *
 * @param max           client machines allowed at once; 0 keeps this till to itself
 * @param discoveryPort UDP port a client shouts on to find the server, so a shop never has to
 *                      type an address
 * @param idleMinutes   how long a client that has stopped talking still holds its place in the
 *                      count. Short enough that a machine carried away or reimaged frees its slot
 *                      the same evening, long enough to survive a reboot or a lunch break.
 */
@Validated
@ConfigurationProperties(prefix = "pos.clients")
public record ClientProperties(@Min(0) @Max(50) int max, @Min(1) int discoveryPort, @Min(1) int idleMinutes) {

    /** True when this machine is the shop's server rather than a till on its own. */
    public boolean serving() {
        return max > 0;
    }
}
