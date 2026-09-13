package com.masspos.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * The server only listens on loopback, but the browser on the till can still be turned against it:
 * <ul>
 *   <li>DNS rebinding: a page on evil.example re-resolves its own name to 127.0.0.1 and becomes
 *       "same-origin" with this server. Rejecting any Host other than a loopback name stops it.</li>
 *   <li>Cross-site POSTs, e.g. a hidden form that opens the cash drawer. A browser cannot send a
 *       custom header cross-origin without a CORS preflight, and this server approves none.</li>
 * </ul>
 *
 * <p>Runs before {@link com.masspos.auth.AuthFilter}: a request from the wrong origin is refused
 * before any token is looked at.
 */
@Component
@Order(10)
public class LocalApiGuard extends OncePerRequestFilter {

    /** Required, with any value, on every state-changing /api request. */
    public static final String CLIENT_HEADER = "X-POS-Client";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!LOOPBACK_HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
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
