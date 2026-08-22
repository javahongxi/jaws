package org.hongxi.jaws.transport.grpc;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.serialization.ObjectInput;
import org.hongxi.jaws.serialization.ObjectOutput;
import org.hongxi.jaws.serialization.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Serializes and deserializes Jaws {@link Request} / {@link Response} objects
 * to and from byte arrays for gRPC transport.
 * <p>
 * The gRPC proto uses a generic {@code bytes payload} field; this class bridges
 * the gap between Jaws' object model and the byte-level gRPC payload using
 * the configured {@link Serialization} SPI (hessian2 or fastjson2).
 *
 * @author shenhongxi
 */
class GrpcPayloadCodec {
    private static final Logger log = LoggerFactory.getLogger(GrpcPayloadCodec.class);

    private GrpcPayloadCodec() {
    }

    // ==================== Request ====================

    /**
     * Serialize a {@link Request} into a byte array.
     */
    static byte[] encodeRequest(Request request, Serialization serialization) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutput out = serialization.serialize(bos)) {
            out.writeUTF(request.getInterfaceName());
            out.writeUTF(request.getMethodName());
            out.writeUTF(request.getParamDesc());
            out.writeLong(request.getRequestId());
            out.writeInt(request.getRetries());
            out.writeLong(serialization.getSerializationNumber());

            // arguments
            Object[] args = request.getArguments();
            if (args == null) {
                out.writeInt(0);
            } else {
                out.writeInt(args.length);
                for (Object arg : args) {
                    out.writeObject(arg);
                }
            }

            // attachments
            Map<String, String> attachments = request.getAttachments();
            if (attachments == null || attachments.isEmpty()) {
                out.writeInt(0);
            } else {
                out.writeInt(attachments.size());
                for (Map.Entry<String, String> entry : attachments.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
            }

            out.flush();
        }
        return bos.toByteArray();
    }

    /**
     * Deserialize a byte array into a {@link DefaultRequest}.
     */
    static DefaultRequest decodeRequest(byte[] payload, Serialization serialization)
            throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(payload);
        try (ObjectInput in = serialization.deserialize(bis)) {
            DefaultRequest request = new DefaultRequest();
            request.setInterfaceName(in.readUTF());
            request.setMethodName(in.readUTF());
            request.setParamDesc(in.readUTF());
            request.setRequestId(in.readLong());
            request.setRetries(in.readInt());
            // skip serialization number from payload (already determined by the serialization param)
            in.readLong();

            // arguments
            int argCount = in.readInt();
            if (argCount > 0) {
                Object[] args = new Object[argCount];
                for (int i = 0; i < argCount; i++) {
                    args[i] = in.readObject();
                }
                request.setArguments(args);
            }

            // attachments
            int attachCount = in.readInt();
            if (attachCount > 0) {
                Map<String, String> attachments = new HashMap<>(attachCount);
                for (int i = 0; i < attachCount; i++) {
                    attachments.put(in.readUTF(), in.readUTF());
                }
                request.setAttachments(attachments);
            }

            return request;
        }
    }

    // ==================== Response ====================

    /**
     * Serialize a {@link Response} into a byte array.
     */
    static byte[] encodeResponse(Response response, Serialization serialization) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutput out = serialization.serialize(bos)) {
            out.writeLong(response.getRequestId());
            out.writeLong(response.getProcessTime());

            // value (maybe null)
            boolean hasValue = false;
            if (response instanceof DefaultResponse dr) {
                // Access the raw value field without triggering exception throw
                Object val = getRawValue(dr);
                if (val != null) {
                    hasValue = true;
                }
                out.writeLong(hasValue ? 1 : 0);
                if (hasValue) {
                    out.writeObject(val);
                }
            } else {
                out.writeLong(0);
            }

            // exception
            Exception ex = response.getException();
            if (ex != null) {
                out.writeLong(1);
                out.writeObject(ex);
            } else {
                out.writeLong(0);
            }

            // attachments
            Map<String, String> attachments = response.getAttachments();
            if (attachments == null || attachments.isEmpty()) {
                out.writeInt(0);
            } else {
                out.writeInt(attachments.size());
                for (Map.Entry<String, String> entry : attachments.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
            }

            out.flush();
        }
        return bos.toByteArray();
    }

    /**
     * Deserialize a byte array into a {@link DefaultResponse}.
     */
    static DefaultResponse decodeResponse(byte[] payload, Serialization serialization)
            throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(payload);
        try (ObjectInput in = serialization.deserialize(bis)) {
            DefaultResponse response = new DefaultResponse();
            response.setRequestId(in.readLong());
            response.setProcessTime(in.readLong());

            // value
            long hasValue = in.readLong();
            if (hasValue == 1) {
                response.setValue(in.readObject());
            }

            // exception
            long hasException = in.readLong();
            if (hasException == 1) {
                Object exObj = in.readObject();
                if (exObj instanceof Exception ex) {
                    response.setException(ex);
                } else if (exObj != null) {
                    response.setException(new RuntimeException(String.valueOf(exObj)));
                }
            }

            // attachments
            int attachCount = in.readInt();
            if (attachCount > 0) {
                Map<String, String> attachments = new HashMap<>(attachCount);
                for (int i = 0; i < attachCount; i++) {
                    attachments.put(in.readUTF(), in.readUTF());
                }
                response.setAttachments(attachments);
            }

            return response;
        }
    }

    /**
     * Resolve the {@link Serialization} instance from the URL parameter.
     */
    static Serialization resolveSerialization(URL url) {
        String serializationName = url.getParameter(URLParamType.serialization);
        return ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(serializationName);
    }

    /**
     * Access the raw value field of a DefaultResponse without triggering
     * the exception-throw behavior of {@link DefaultResponse#getValue()}.
     */
    private static Object getRawValue(DefaultResponse response) {
        try {
            java.lang.reflect.Field field = DefaultResponse.class.getDeclaredField("value");
            field.setAccessible(true);
            return field.get(response);
        } catch (Exception e) {
            log.warn("Failed to access raw value field via reflection, falling back to JSON", e);
            return null;
        }
    }
}
