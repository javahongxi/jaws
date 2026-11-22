package org.hongxi.jaws.wire;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import org.hongxi.jaws.metadata.MetadataInfo;
import org.hongxi.jaws.metadata.MetadataService;

import java.util.HashMap;
import java.util.Map;

/**
 * Wire envelope for the internal {@link MetadataService}, the point-to-point
 * metadata pull channel of application-level service discovery.
 * <p>
 * Unlike business wire services, {@code MetadataService} is a plain Java
 * interface ({@code String revision -> MetadataInfo}) with no protobuf types,
 * so it cannot go through {@link WireProtoTypes#fromServiceInterface} directly.
 * Both legs ride the well-known {@link StringValue} wrapper instead:
 * <ul>
 *   <li>request — the revision string as-is</li>
 *   <li>response — the {@link MetadataInfo} serialized as JSON (the same shape
 *       as Dubbo's internal {@code MetadataResponse.value}); an empty string
 *       means the provider has never published that revision (null)</li>
 * </ul>
 * The provider pipeline keeps carrying the plain Java types: the server side
 * unwraps the revision before the filter chain and wraps the return value
 * after it ({@link WireMessageHandler}), the consumer side does the mirror
 * image in {@link WireReference}.
 *
 * @author shenhongxi
 */
final class WireMetadataCodec {

    /** The single method of the internal metadata pull interface. */
    private static final String METHOD_NAME = "getMetadataInfo";

    private WireMetadataCodec() {
    }

    /**
     * Synthetic protobuf metadata for {@code MetadataService}: one unary
     * method whose request and response are both {@link StringValue},
     * registered under the Java camelCase and gRPC PascalCase names like
     * any business service.
     */
    static WireProtoTypes protoTypes() {
        WireProtoTypes.MethodInfo info = new WireProtoTypes.MethodInfo(
                StringValue.class, StringValue.class,
                StringValue.parser(), StringValue.parser(), false, false);
        Map<String, WireProtoTypes.MethodInfo> map = new HashMap<>();
        map.put(METHOD_NAME, info);
        map.put(WireProtoTypes.toGrpcMethodName(METHOD_NAME), info);
        return new WireProtoTypes(map);
    }

    /**
     * Consumer side: wrap the plain {@code String revision} argument into the
     * request envelope.
     */
    static Object[] encodeArguments(Object[] arguments) {
        return new Object[]{StringValue.of((String) arguments[0])};
    }

    /**
     * Provider side: unwrap the revision the filter chain and implementation
     * expect as a plain {@code String}.
     */
    static String decodeRevision(Message request) {
        return ((StringValue) request).getValue();
    }

    /**
     * Provider side: wrap the {@link MetadataInfo} return value into the
     * response envelope; null (unknown revision) becomes the empty string.
     */
    static Message encodeMetadataInfo(Object metadataInfo) {
        if (metadataInfo == null) {
            return StringValue.getDefaultInstance();
        }
        return StringValue.of(JSON.toJSONString(metadataInfo));
    }

    /**
     * Consumer side: unwrap the response envelope back into a
     * {@link MetadataInfo}; the empty string decodes to null.
     */
    static MetadataInfo decodeMetadataInfo(Message response) {
        String json = ((StringValue) response).getValue();
        if (json.isEmpty()) {
            return null;
        }
        return JSON.parseObject(json, MetadataInfo.class);
    }
}