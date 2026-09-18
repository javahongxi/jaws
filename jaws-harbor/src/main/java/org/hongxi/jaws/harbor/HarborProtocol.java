package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.Response;
import org.hongxi.jaws.harbor.proto.Metadata;
import org.hongxi.jaws.harbor.proto.Payload;

import java.nio.charset.StandardCharsets;

/**
 * The harbor wire contract: RPC names, payload type tokens, and the Payload
 * codec shared by the server, the Distro peer transport and any client.
 * <p>
 * Token rule, borrowed from Nacos ({@code GrpcUtils} on the client side,
 * {@code RequestHandlerRegistry} on the server side): {@code metadata.type}
 * is always the simple name of the DTO class carried in the JSON body, so
 * {@link #typeToken(Class)} is the only way a token may be produced.
 * Deriving a token instead of writing it down is what stops the two ends from
 * drifting apart on a name the other has never seen.
 *
 * @author shenhongxi
 */
public final class HarborProtocol {

    /** Unary RPC that every naming and Distro request rides on. */
    public static final String RPC_UNARY_SERVICE = "Request";
    public static final String RPC_UNARY_METHOD = "request";

    /** Bidirectional stream: connection setup upstream, pushes downstream. */
    public static final String RPC_STREAM_SERVICE = "BiRequestStream";
    public static final String RPC_STREAM_METHOD = "requestBiStream";

    /**
     * The one token with no DTO of its own: config listens are answered
     * without their body ever being deserialized, so there is no class to
     * name. Kept here so the exception is stated once, in words.
     */
    public static final String CONFIG_LISTEN_REQUEST = "ConfigBatchListenRequest";

    /**
     * Values of {@code InstanceRequest.type} / {@code BatchInstanceRequest.type}
     * (Nacos {@code NamingRemoteConstants}).
     */
    public static final String REGISTER_INSTANCE = "registerInstance";
    public static final String DEREGISTER_INSTANCE = "deregisterInstance";
    public static final String BATCH_REGISTER_INSTANCE = "batchRegisterInstance";

    private HarborProtocol() {
    }

    /**
     * Wire token of a contract DTO, which is simply its class name.
     */
    public static String typeToken(Class<?> contractType) {
        return contractType.getSimpleName();
    }

    /**
     * Read the JSON body into its typed form. An empty body yields a default
     * instance, which is how a bare {@code HealthCheckRequest} arrives.
     */
    public static <T> T parseBody(Payload payload, Class<T> contractType) {
        byte[] bytes = payload.getBody().getValue().toByteArray();
        if (bytes.length == 0) {
            try {
                return contractType.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to create empty " + contractType.getSimpleName(), e);
            }
        }
        return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), contractType);
    }

    /**
     * Wrap an outbound request, e.g. a Distro sync sent to a peer node.
     */
    public static Payload encodeRequest(Request request) {
        return wrap(typeToken(request.getClass()), request, null);
    }

    /**
     * Wrap a reply to a unary request.
     */
    public static Payload encodeResponse(Response response) {
        return wrap(typeToken(response.getClass()), response, null);
    }

    /**
     * Wrap a reply that echoes the caller's address back to it.
     */
    public static Payload encodeResponse(Response response, String clientIp) {
        return wrap(typeToken(response.getClass()), response, clientIp);
    }

    /**
     * Wrap a server-initiated push. Nacos casts every frame on the bi-stream
     * to {@code Request}, so a push is a request DTO and never a response.
     */
    public static Payload encodePush(Request pushRequest) {
        return wrap(typeToken(pushRequest.getClass()), pushRequest, null);
    }

    /**
     * Wrap a failure. {@code responseType} is the token the caller was
     * expecting, so it finds the message on the reply it had planned for.
     */
    public static Payload encodeError(String responseType, String message) {
        return wrap(responseType, new ErrorBody(message), null);
    }

    private static Payload wrap(String type, Object body, String clientIp) {
        byte[] jsonBytes = JSON.toJSONBytes(body);
        Metadata.Builder metadata = Metadata.newBuilder().setType(type);
        if (clientIp != null) {
            metadata.setClientIp(clientIp);
        }
        return Payload.newBuilder()
                .setMetadata(metadata)
                .setBody(Any.newBuilder().setValue(ByteString.copyFrom(jsonBytes)))
                .build();
    }

    private record ErrorBody(String message) {
    }
}
