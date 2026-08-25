package org.hongxi.jaws.transport.http2;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Utility for gRPC wire format compatibility within the HTTP/2 transport.
 * <p>
 * Handles the gRPC 5-byte length-prefix framing (1 byte compression flag +
 * 4 bytes big-endian message length), gRPC path parsing
 * ({@code /{package}.{Service}/{Method}}), and JSON-to-Java argument
 * conversion for {@code application/grpc+json} requests.
 * <p>
 * This codec enables standard gRPC clients (using JSON encoding) to call
 * Jaws services without any proto schema dependency on the server side.
 *
 * @author shenhongxi
 */
public final class GrpcCodec {

    private GrpcCodec() {
    }

    // ==================== Frame encoding / decoding ====================

    /**
     * Decode a gRPC length-prefixed message.
     *
     * @param data the raw DATA payload (5-byte prefix + message body)
     * @return the message body bytes
     */
    public static byte[] decodeFrame(byte[] data) {
        if (data == null || data.length < 5) {
            throw new IllegalArgumentException("Invalid gRPC frame: length=" + (data == null ? 0 : data.length));
        }
        // byte 0: compressed flag (ignored, we don't support compression yet)
        int length = ((data[1] & 0xFF) << 24)
                | ((data[2] & 0xFF) << 16)
                | ((data[3] & 0xFF) << 8)
                | (data[4] & 0xFF);
        if (data.length < 5 + length) {
            throw new IllegalArgumentException(
                    "gRPC frame truncated: expected=" + (5 + length) + " actual=" + data.length);
        }
        byte[] message = new byte[length];
        System.arraycopy(data, 5, message, 0, length);
        return message;
    }

    /**
     * Encode a message into gRPC length-prefixed format (no compression).
     *
     * @param message the message body bytes
     * @return the framed bytes (5-byte prefix + message)
     */
    public static byte[] encodeFrame(byte[] message) {
        byte[] frame = new byte[5 + message.length];
        frame[0] = 0; // not compressed
        frame[1] = (byte) ((message.length >> 24) & 0xFF);
        frame[2] = (byte) ((message.length >> 16) & 0xFF);
        frame[3] = (byte) ((message.length >> 8) & 0xFF);
        frame[4] = (byte) (message.length & 0xFF);
        System.arraycopy(message, 0, frame, 5, message.length);
        return frame;
    }

    // ==================== Path parsing ====================

    /**
     * Parse a gRPC path into {@code [serviceName, methodName]}.
     * <p>
     * gRPC uses {@code /{package}.{Service}/{Method}} as the HTTP/2 {@code :path}.
     *
     * @param path the HTTP/2 path pseudo-header value
     * @return a two-element array: [interfaceName, methodName]
     * @throws IllegalArgumentException if the path format is invalid
     */
    public static String[] parsePath(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid gRPC path: " + path);
        }
        String trimmed = path.substring(1);
        int slashIdx = trimmed.lastIndexOf('/');
        if (slashIdx <= 0 || slashIdx >= trimmed.length() - 1) {
            throw new IllegalArgumentException("Invalid gRPC path: " + path);
        }
        return new String[]{trimmed.substring(0, slashIdx), trimmed.substring(slashIdx + 1)};
    }

    // ==================== JSON → arguments ====================

    /**
     * Convert a JSON message body into an argument array matching the given
     * method's parameter types.
     * <p>
     * If the JSON is an object, each field is matched to the corresponding
     * parameter by position (field order in the JSON). If the JSON is an
     * array, elements are matched positionally.
     *
     * @param json   the JSON string from the gRPC DATA frame
     * @param method the target Java method
     * @return the converted argument array, or {@code null} if no parameters
     */
    public static Object[] jsonToArguments(String json, Method method) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return null;
        }

        Object parsed = JSON.parse(json);
        if (parsed instanceof JSONObject jsonObj) {
            return jsonObjectToArray(jsonObj, paramTypes);
        } else if (parsed instanceof com.alibaba.fastjson2.JSONArray jsonArr) {
            return jsonArrayToArray(jsonArr, paramTypes);
        }
        // Single scalar value for single-parameter methods
        if (paramTypes.length == 1) {
            return new Object[]{convertValue(parsed, paramTypes[0])};
        }
        throw new IllegalArgumentException(
                "Cannot map JSON " + parsed.getClass().getSimpleName()
                        + " to method with " + paramTypes.length + " parameters");
    }

    private static Object[] jsonObjectToArray(JSONObject jsonObj, Class<?>[] paramTypes) {
        Object[] args = new Object[paramTypes.length];
        int i = 0;
        for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
            if (i >= paramTypes.length) break;
            args[i] = convertValue(entry.getValue(), paramTypes[i]);
            i++;
        }
        return args;
    }

    private static Object[] jsonArrayToArray(com.alibaba.fastjson2.JSONArray jsonArr, Class<?>[] paramTypes) {
        Object[] args = new Object[Math.min(jsonArr.size(), paramTypes.length)];
        for (int i = 0; i < args.length; i++) {
            args[i] = convertValue(jsonArr.get(i), paramTypes[i]);
        }
        return args;
    }

    /**
     * Convert a single JSON value to the target Java type.
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return value.toString();
        }
        if (value instanceof Number num) {
            if (targetType == int.class || targetType == Integer.class) return num.intValue();
            if (targetType == long.class || targetType == Long.class) return num.longValue();
            if (targetType == double.class || targetType == Double.class) return num.doubleValue();
            if (targetType == float.class || targetType == Float.class) return num.floatValue();
            if (targetType == short.class || targetType == Short.class) return num.shortValue();
            if (targetType == byte.class || targetType == Byte.class) return num.byteValue();
            if (targetType == boolean.class || targetType == Boolean.class) return num.intValue() != 0;
        }
        if (value instanceof String str) {
            if (targetType == boolean.class || targetType == Boolean.class) return Boolean.parseBoolean(str);
            if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(str);
            if (targetType == long.class || targetType == Long.class) return Long.parseLong(str);
            if (targetType == double.class || targetType == Double.class) return Double.parseDouble(str);
        }
        // Complex object: let fastjson2 handle the conversion
        return JSON.to(targetType, value);
    }

    // ==================== Content-type detection ====================

    /**
     * Check whether the given content-type indicates a gRPC request.
     */
    public static boolean isGrpcContentType(String contentType) {
        return contentType != null && contentType.startsWith(Http2Constants.GRPC_CONTENT_TYPE);
    }

    /**
     * Check whether the content-type indicates gRPC+JSON encoding.
     */
    public static boolean isGrpcJson(String contentType) {
        return contentType != null && contentType.startsWith(Http2Constants.GRPC_JSON_CONTENT_TYPE);
    }
}
