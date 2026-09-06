package org.hongxi.jaws.transport.http.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PathPattern}.
 */
class PathPatternTest {

    @Test
    void exactPathMatch() {
        PathPattern pattern = new PathPattern("/users");
        assertNotNull(pattern.match("/users"));
        assertNull(pattern.match("/users/"));
        assertNull(pattern.match("/user"));
        assertNull(pattern.match("/users/1"));
    }

    @Test
    void singlePathVariable() {
        PathPattern pattern = new PathPattern("/users/{id}");
        PathPattern.MatchResult result = pattern.match("/users/42");
        assertNotNull(result);
        assertEquals("42", result.getPathVariables().get("id"));

        assertNull(pattern.match("/users"));
        assertNull(pattern.match("/users/42/orders"));
    }

    @Test
    void multiplePathVariables() {
        PathPattern pattern = new PathPattern("/users/{userId}/orders/{orderId}");
        PathPattern.MatchResult result = pattern.match("/users/1/orders/99");
        assertNotNull(result);
        assertEquals("1", result.getPathVariables().get("userId"));
        assertEquals("99", result.getPathVariables().get("orderId"));

        assertNull(pattern.match("/users/1/orders"));
        assertNull(pattern.match("/users/1"));
    }

    @Test
    void singleSegmentWildcard() {
        PathPattern pattern = new PathPattern("/files/*");
        assertNotNull(pattern.match("/files/readme.txt"));
        assertNotNull(pattern.match("/files/anything"));
        assertNull(pattern.match("/files/dir/file.txt"));
        assertNull(pattern.match("/files"));
    }

    @Test
    void multiSegmentWildcard() {
        PathPattern pattern = new PathPattern("/api/**");
        assertNotNull(pattern.match("/api/users"));
        assertNotNull(pattern.match("/api/users/1/orders"));
        assertNull(pattern.match("/api"));
        assertNull(pattern.match("/other"));
    }

    @Test
    void rootPath() {
        PathPattern pattern = new PathPattern("/");
        assertNotNull(pattern.match("/"));
        assertNull(pattern.match("/users"));
    }

    @Test
    void variableNames() {
        PathPattern pattern = new PathPattern("/users/{userId}/orders/{orderId}");
        assertEquals(2, pattern.getVariableNames().size());
        assertEquals("userId", pattern.getVariableNames().get(0));
        assertEquals("orderId", pattern.getVariableNames().get(1));
    }

    @Test
    void noVariables() {
        PathPattern pattern = new PathPattern("/health");
        assertTrue(pattern.getVariableNames().isEmpty());
    }

    @Test
    void pathWithSpecialRegexCharacters() {
        PathPattern pattern = new PathPattern("/api/v1.0/users");
        assertNotNull(pattern.match("/api/v1.0/users"));
        // the dot should NOT match any character (it's escaped)
        assertNull(pattern.match("/api/v1X0/users"));
    }
}
