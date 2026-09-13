package com.masspos.auth;

import com.masspos.audit.AuditActor;
import com.masspos.audit.AuditContext;
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
 * Turns the bearer token into the signed-in user for this request, and binds that user to the audit
 * trail, so every invoice and stock movement records who made it (MCA Rule 11(g)).
 *
 * <p>Runs after {@link com.masspos.web.LocalApiGuard}. The UI files and sign-in are public;
 * everything else under /api needs a token.
 */
@Component
@Order(20)
public class AuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login");
    /** All a user may do while their account still carries a forced PIN change. */
    private static final Set<String> PIN_CHANGE_PATHS =
            Set.of("/api/auth/change-pin", "/api/auth/logout", "/api/auth/me");

    private final AuthService auth;

    public AuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || PUBLIC_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        Principal principal = auth.resolve(bearerToken(request)).orElse(null);
        if (principal == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Sign in first");
            return;
        }
        // A new or reset account cannot bill until its owner picks their own PIN, otherwise the
        // handover PIN would keep signing invoices in that person's name.
        if (principal.mustChangePin() && !PIN_CHANGE_PATHS.contains(path)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Change your PIN first");
            return;
        }
        AuthContext.bind(principal);
        AuditContext.bind(new AuditActor(principal.userId(), principal.username()));
        try {
            chain.doFilter(request, response);
        } finally {
            AuditContext.clear();
            AuthContext.clear();
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith(PREFIX) ? header.substring(PREFIX.length()).trim() : null;
    }
}
