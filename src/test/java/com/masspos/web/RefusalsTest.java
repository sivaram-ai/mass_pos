package com.masspos.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a filter answers with.
 *
 * <p>A filter runs before any controller, so {@link ApiExceptionHandler} never sees it and it has
 * to write its own body. The obvious way - {@code response.sendError(status, message)} - silently
 * drops the message (Boot's {@code server.error.include-message} defaults to {@code never}) and
 * hands the screen the container's own error body instead: a status number, the word
 * "Unauthorized", the path that was asked for, a UTC timestamp. The sentence somebody wrote never
 * arrived at all, which is how a cashier ended up reading {@code {"status":401,...,"path":
 * "/api/products"}}.
 */
class RefusalsTest {

    @Test
    void answersWithASentenceAndNothingElse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Refusals.unauthenticated(response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"detail\":\"You have been signed out. Sign in again to carry on.\"")
                // Not the path asked for, not a timestamp, not the framework's word for the status.
                .doesNotContain("path", "timestamp", "Unauthorized", "exception");
    }

    /** The body is hand-written, so a quote in a message must not be able to break it. */
    @Test
    void cannotBeBrokenByAQuoteInTheMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Refusals.write(response, 403, "No", "Open \"GO RockIt\" from its own shortcut.");

        assertThat(response.getContentAsString())
                .contains("Open \\\"GO RockIt\\\" from its own shortcut.");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.getContentAsString()).get("detail").asText())
                .isEqualTo("Open \"GO RockIt\" from its own shortcut.");
    }
}
