package org.hongxi.jaws.transport.http.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight Ant-style path pattern matcher that supports {@code {var}} path
 * variable extraction, {@code *} single-segment wildcard, and {@code **}
 * multi-segment wildcard.
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code /users/{id}} matches {@code /users/42} → {id=42}</li>
 *   <li>{@code /users/{id}/orders/{orderId}} matches {@code /users/1/orders/99}</li>
 *   <li>{@code /files/*} matches {@code /files/readme.txt}</li>
 *   <li>{@code /api/**} matches {@code /api/users/1/orders}</li>
 * </ul>
 *
 * @author shenhongxi
 */
public class PathPattern {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([^/]+?)}");

    private final String pattern;
    private final Pattern compiled;
    private final List<String> variableNames;

    public PathPattern(String pattern) {
        this.pattern = pattern;
        this.variableNames = extractVariableNames(pattern);
        this.compiled = compile(pattern);
    }

    /**
     * Attempt to match the given path against this pattern.
     *
     * @param path the request path (e.g. {@code /users/42})
     * @return a match result containing extracted path variables, or {@code null} if no match
     */
    public MatchResult match(String path) {
        Matcher matcher = compiled.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        Map<String, String> variables = new HashMap<>();
        for (int i = 0; i < variableNames.size(); i++) {
            variables.put(variableNames.get(i), matcher.group(i + 1));
        }
        return new MatchResult(variables);
    }

    public String getPattern() {
        return pattern;
    }

    public List<String> getVariableNames() {
        return variableNames;
    }

    private static List<String> extractVariableNames(String pattern) {
        Matcher matcher = VARIABLE_PATTERN.matcher(pattern);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Compile the pattern string into a regex.
     * <ul>
     *   <li>{@code {var}} → {@code ([^/]+)} capturing group</li>
     *   <li>{@code **} → {@code (.+)} multi-segment wildcard</li>
     *   <li>{@code *} → {@code ([^/]+)} single-segment wildcard</li>
     * </ul>
     */
    private static Pattern compile(String pattern) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                // path variable
                int end = pattern.indexOf('}', i);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed '{' in path pattern: " + pattern);
                }
                regex.append("([^/]+)");
                i = end + 1;
            } else if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    regex.append("(.+)");
                    i += 2;
                    // skip trailing slash after **
                    if (i < pattern.length() && pattern.charAt(i) == '/') {
                        i++;
                    }
                } else {
                    regex.append("([^/]+)");
                    i++;
                }
            } else {
                // literal character — escape regex special chars
                if ("\\.[]()^$?+|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
                i++;
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * Result of a successful path match, containing extracted path variables.
     */
    public static class MatchResult {
        private final Map<String, String> pathVariables;

        public MatchResult(Map<String, String> pathVariables) {
            this.pathVariables = Collections.unmodifiableMap(pathVariables);
        }

        public Map<String, String> getPathVariables() {
            return pathVariables;
        }
    }
}
