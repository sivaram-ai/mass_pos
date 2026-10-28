package com.masspos.web;

import com.masspos.client.ClientProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Who may talk to this till at all, decided before any token is read. The browser on the till can
 * be turned against it:
 * <ul>
 *   <li>DNS rebinding: a page on evil.example re-resolves its own name to this machine's address
 *       and becomes "same-origin" with the server. The Host header still says evil.example, and
 *       that is not a name this machine answers to, so it is refused.</li>
 *   <li>Cross-site POSTs, e.g. a hidden form that opens the cash drawer. A browser cannot send a
 *       custom header cross-origin without a CORS preflight, and this server approves none.</li>
 * </ul>
 *
 * <p>A till on its own accepts loopback and nothing else. A shop server - {@code pos.clients.max}
 * above 0 - also accepts the addresses and names it holds on the LAN, because that is what its
 * counters connect to. What widens is the set of names, never the check itself.
 *
 * <p>Runs before {@link com.masspos.auth.AuthFilter}: a request from the wrong origin is refused
 * before any token is looked at.
 */
@Component
@Order(10)
public class LocalApiGuard extends OncePerRequestFilter {

    /** Required, with any value, on every state-changing /api request. */
    public static final String CLIENT_HEADER = "X-POS-Client";

    /** Which counter the machine making the request is. Absent from the server's own browser. */
    public static final String COUNTER_HEADER = "X-POS-Counter";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AllowedHosts hosts;

    public LocalApiGuard(ClientProperties clients) {
        this.hosts = clients.serving() ? AllowedHosts.thisMachineOnTheLan() : AllowedHosts.loopbackOnly();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hosts.accepts(request.getServerName())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Host not allowed");
            return;
        }
        if (request.getRequestURI().startsWith("/api/") && !SAFE_METHODS.contains(request.getMethod())
                && request.getHeader(CLIENT_HEADER) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing " + CLIENT_HEADER + " header");
            return;
        }
        chain.doFilter(request, response);
    }
}
