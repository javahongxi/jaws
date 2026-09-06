package org.hongxi.jaws.transport.http.rest;

/**
 * Describes how a single method parameter should be bound from an HTTP request.
 * <p>
 * Each parameter has a {@link Source} indicating where its value comes from
 * (path variable, query parameter, request body, or no binding), a name used
 * to look up the value, and the target Java type for conversion.
 *
 * @author shenhongxi
 */
public class ParameterBinding {

    /**
     * Where the parameter value comes from.
     */
    public enum Source {
        /** Extracted from the URL path via {@code {var}} pattern. */
        PATH_VARIABLE,
        /** Extracted from the URL query string. */
        QUERY_PARAM,
        /** Parsed from the JSON request body. */
        REQUEST_BODY,
        /** No binding — parameter will be {@code null}. */
        NONE
    }

    private final Source source;
    private final String name;
    private final Class<?> targetType;

    public ParameterBinding(Source source, String name, Class<?> targetType) {
        this.source = source;
        this.name = name;
        this.targetType = targetType;
    }

    public Source getSource() {
        return source;
    }

    public String getName() {
        return name;
    }

    public Class<?> getTargetType() {
        return targetType;
    }

    @Override
    public String toString() {
        return "ParameterBinding{source=" + source + ", name='" + name + "', type=" + targetType.getSimpleName() + "}";
    }
}
