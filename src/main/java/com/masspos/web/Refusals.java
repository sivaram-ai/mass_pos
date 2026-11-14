package com.masspos.web;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * How a filter refuses, in the same shape {@link ApiExceptionHandler} uses.
 *
 * <p>A filter runs before any controller, so nothing it throws reaches the handler: it has to write
 * its own answer. {@code response.sendError(status, message)} looks like the way to do that and is
 * not. Boot's {@code server.error.include-message} defaults to {@code never}, so the message is
 * dropped and what the screen receives is the container's own error body - a status number, the
 * word "Unauthorized", the path that was asked for and a UTC timestamp. None of it was written for
 * a shop, and the sentence that was never arrived at all.
 *
 * <p>So the body is written here, as a {@code ProblemDetail} with a {@code detail} a cashier can
 * act on and nothing else in it. Whatever the real reason was - a token that is not ours, a Host
 * header that is not this machine's - goes to the filter's log, not down the wire: a refusal is
 * also the one answer an attacker gets, and it should tell them no more than it tells the shop.
 */
public final class Refusals {

    private Refusals() {
    }

    /** Nobody is signed in, or the token has been revoked. */
    public static void unauthenticated(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "Sign in required",
                "You have been signed out. Sign in again to carry on.");
    }

    /** A new or reset account, which cannot do anything until its owner picks their own PIN. */
    public static void pinChangeFirst(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "Choose your PIN first",
                "Choose your own PIN before carrying on.");
    }

    /** Writes the whole answer; the caller must not touch the response afterwards. */
    public static void write(HttpServletResponse response, int status, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + escape(title)
                + "\",\"status\":" + status + ",\"detail\":\"" + escape(detail) + "\"}");
        response.getWriter().flush();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
