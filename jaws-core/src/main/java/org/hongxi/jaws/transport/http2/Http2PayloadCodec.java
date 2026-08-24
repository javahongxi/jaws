package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.serialization.ObjectInput;
import org.hongxi.jaws.serialization.ObjectOutput;
import org.hongxi.jaws.serialization.Serialization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Serializes and deserializes Jaws {@link Request} / {@link Response} objects
 * to and from byte arrays carried in HTTP/2 DATA frames.
 * <p>
 * HTTP/2 handles all framing, so this codec only deals with the payload body
 * using the configured {@link Serialization} SPI. Compared with the native jaws
 * protocol (which writes directly into pooled Netty {@code ByteBuf}s), this
 * adapter necessarily materializes the payload as a {@code byte[]}; that is an
 * inherent cost of the stream-oriented HTTP/2 boundary, not a codec-layer
 * inefficiency — zero-copy holds only on the native protocol path.
 *
 * @author shenhongxi
 */
public final class Http2PayloadCodec {

    private Http2PayloadCodec() {
    }

    // ==================== Request ====================

    /**
     * Serialize a {@link Request} into a byte array.
     */
    public static byte[] encodeRequest(Request request, Serialization serialization) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutput out = serialization.serialize(bos)) {
            out.writeUTF(request.getInterfaceName());
            out.writeUTF(request.getMethodName());
            out.writeUTF(request.getParamDesc());
            out.writeLong(request.getRequestId());
            out.writeInt(request.getRetries());

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
    public static DefaultRequest decodeRequest(byte[] payload, Serialization serialization)
            throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(payload);
        try (ObjectInput in = serialization.deserialize(bis)) {
            DefaultRequest request = new DefaultRequest();
            request.setInterfaceName(in.readUTF());
            request.setMethodName(in.readUTF());
            request.setParamDesc(in.readUTF());
            request.setRequestId(in.readLong());
            request.setRetries(in.readInt());

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
    public static byte[] encodeResponse(Response response, Serialization serialization) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutput out = serialization.serialize(bos)) {
            out.writeLong(response.getRequestId());
            out.writeLong(response.getProcessTime());

            // value (maybe null)
            Object val = response.getRawValue();
            boolean hasValue = val != null;
            out.writeLong(hasValue ? 1 : 0);
            if (hasValue) {
                out.writeObject(val);
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
    public static DefaultResponse decodeResponse(byte[] payload, Serialization serialization)
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
     * Resolve a {@link Serialization} instance by its SPI name.
     */
    public static Serialization resolveSerialization(String name) {
        return ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(name);
    }
}
