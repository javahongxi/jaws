package org.hongxi.jaws.protocol.jaws;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import org.hongxi.jaws.codec.AbstractCodec;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.transport.Channel;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.HashMap;
import java.util.Map;

/**
 * Codec implementation for the Jaws protocol.
 * <p>
 * Protocol header layout (16 bytes):
 * <pre>
 * Bytes 0-1   : magic (0xF0F0)
 * Byte  2     : version
 * Byte  3     : flag (low 3 bits = data type, high 5 bits = serializationId)
 * Bytes 4-11  : request id
 * Bytes 12-15 : body content length
 * </pre>
 */
@SpiMeta(name = "jaws")
public class JawsCodec extends AbstractCodec {

    public static final int HEADER_LENGTH = 16;
    public static final byte VERSION = 1;
    public static final short MAGIC = (short) 0xF0F0;
    public static final byte MASK = 0x07;
    public static final byte SERIALIZATION_MASK = (byte) 0xF8;

    public static final byte FLAG_REQUEST = 0x00;
    public static final byte FLAG_RESPONSE = 0x01;
    public static final byte FLAG_RESPONSE_VOID = 0x03;
    public static final byte FLAG_RESPONSE_EXCEPTION = 0x05;

    @Override
    public void encode(Channel channel, Object message, ByteBuf out) throws IOException {
        try {
            if (message instanceof Request request) {
                encodeRequest(channel, request, out);
            } else if (message instanceof Response response) {
                encodeResponse(channel, response, out);
            } else {
                throw new JawsFrameworkException("encode error: message type not support, " + message.getClass(),
                        JawsErrorMsgConstants.FRAMEWORK_ENCODE_ERROR);
            }
        } catch (JawsAbstractException e) {
            throw e;
        } catch (Exception e) {
            throw new JawsFrameworkException("encode error: isResponse=" + (message instanceof Response),
                    e, JawsErrorMsgConstants.FRAMEWORK_ENCODE_ERROR);
        }
    }

    /**
     * Decode data from client request or server response.
     */
    @Override
    public Object decode(Channel channel, ByteBuf in) throws IOException {
        if (in.readableBytes() <= HEADER_LENGTH) {
            throw new JawsFrameworkException("decode error: format problem",
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        int startIndex = in.readerIndex();

        // bytes 0-1: magic
        short type = in.readShort();
        if (type != MAGIC) {
            throw new JawsFrameworkException("decode error: magic error",
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        // byte 2: version
        byte version = in.readByte();
        if (version != VERSION) {
            throw new JawsFrameworkException("decode error: version error",
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        // byte 3: flag (low 3 bits = data type, high 5 bits = serializationId)
        byte flag = in.readByte();
        byte dataType = (byte) (flag & MASK);
        boolean isResponse = (dataType != FLAG_REQUEST);

        // bytes 4-11: requestId
        long requestId = in.readLong();

        // bytes 12-15: body length
        int bodyLength = in.readInt();

        if (HEADER_LENGTH + bodyLength != in.readableBytes() + (in.readerIndex() - startIndex)) {
            throw new JawsFrameworkException("decode error: content length error",
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        // Slice body region from ByteBuf (zero-copy, no body byte[] allocation)
        ByteBuf bodyBuf = in.retainedSlice(in.readerIndex(), bodyLength);
        in.skipBytes(bodyLength);

        Serialization serialization = ExtensionLoader.getExtensionLoader(Serialization.class)
                .getExtension(channel.getUrl().getParameter(URLParamType.serialization));

        try (ByteBufInputStream bodyIn = new ByteBufInputStream(bodyBuf)) {
            ObjectInput input = createInput(bodyIn);
            if (isResponse) {
                return decodeResponse(input, dataType, requestId, serialization);
            } else {
                return decodeRequest(input, requestId, serialization);
            }
        } catch (ClassNotFoundException e) {
            throw new JawsFrameworkException("decode " + (isResponse ? "response" : "request") +
                    " error: class not found", e, JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        } catch (JawsAbstractException e) {
            throw e;
        } catch (Exception e) {
            throw new JawsFrameworkException("decode error: isResponse=" + isResponse,
                    e, JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        } finally {
            bodyBuf.release();
        }
    }

    /**
     * Encode an RPC request.
     * <p>
     * Body layout: interface_name, method_name, param_desc, serialized param values, attachments.
     */
    private void encodeRequest(Channel channel, Request request, ByteBuf out) throws IOException {
        Serialization serialization = ExtensionLoader.getExtensionLoader(Serialization.class)
                .getExtension(channel.getUrl().getParameter(URLParamType.serialization));

        // Reserve header space
        int headerStart = out.writerIndex();
        out.writerIndex(headerStart + HEADER_LENGTH);

        // Write body directly to ByteBuf
        ByteBufOutputStream bodyOut = new ByteBufOutputStream(out);
        ObjectOutput output = createOutput(bodyOut);
        output.writeUTF(request.getInterfaceName());
        output.writeUTF(request.getMethodName());
        output.writeUTF(request.getParamDesc());

        if (request.getArguments() != null) {
            for (Object obj : request.getArguments()) {
                serialize(output, obj, serialization);
            }
        }

        if (request.getAttachments() == null || request.getAttachments().isEmpty()) {
            output.writeInt(0);
        } else {
            output.writeInt(request.getAttachments().size());
            for (Map.Entry<String, String> entry : request.getAttachments().entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeUTF(entry.getValue());
            }
        }

        output.flush();
        output.close();

        int bodyLength = out.writerIndex() - headerStart - HEADER_LENGTH;

        // Backfill header with serializationId embedded in flag
        byte flag = (byte) (FLAG_REQUEST | ((serialization.getSerializationNumber() << 3) & SERIALIZATION_MASK));
        writeHeader(out, headerStart, flag, request.getRequestId(), bodyLength);
    }

    /**
     * Encode an RPC response.
     * <p>
     * Body layout: process_time, class_name, serialized result or exception.
     */
    private void encodeResponse(Channel channel, Response response, ByteBuf out) throws IOException {
        Serialization serialization = ExtensionLoader.getExtensionLoader(Serialization.class)
                .getExtension(channel.getUrl().getParameter(URLParamType.serialization));

        // Reserve header space
        int headerStart = out.writerIndex();
        out.writerIndex(headerStart + HEADER_LENGTH);

        // Write body directly to ByteBuf
        ByteBufOutputStream bodyOut = new ByteBufOutputStream(out);
        ObjectOutput output = createOutput(bodyOut);
        output.writeLong(response.getProcessTime());

        byte dataType;
        if (response.getException() != null) {
            output.writeUTF(response.getException().getClass().getName());
            serialize(output, response.getException(), serialization);
            dataType = FLAG_RESPONSE_EXCEPTION;
        } else if (response.getValue() == null) {
            dataType = FLAG_RESPONSE_VOID;
        } else {
            output.writeUTF(response.getValue().getClass().getName());
            serialize(output, response.getValue(), serialization);
            dataType = FLAG_RESPONSE;
        }

        output.flush();
        output.close();

        int bodyLength = out.writerIndex() - headerStart - HEADER_LENGTH;

        // Backfill header with serializationId embedded in flag
        byte flag = (byte) (dataType | ((serialization.getSerializationNumber() << 3) & SERIALIZATION_MASK));
        writeHeader(out, headerStart, flag, response.getRequestId(), bodyLength);
    }

    /**
     * Write the 16-byte protocol header at the reserved position in the ByteBuf.
     */
    private void writeHeader(ByteBuf out, int headerStart, byte flag, long requestId, int bodyLength) {
        int currentIndex = out.writerIndex();
        out.writerIndex(headerStart);

        // bytes 0-1: magic
        out.writeShort(MAGIC);
        // byte 2: version
        out.writeByte(VERSION);
        // byte 3: flag (data type + serializationId)
        out.writeByte(flag);
        // bytes 4-11: requestId
        out.writeLong(requestId);
        // bytes 12-15: body content length
        out.writeInt(bodyLength);

        out.writerIndex(currentIndex);
    }

    private Object decodeRequest(ObjectInput input, long requestId, Serialization serialization)
            throws IOException, ClassNotFoundException {
        String interfaceName = input.readUTF();
        String methodName = input.readUTF();
        String paramDesc = input.readUTF();

        DefaultRequest rpcRequest = new DefaultRequest();
        rpcRequest.setRequestId(requestId);
        rpcRequest.setInterfaceName(interfaceName);
        rpcRequest.setMethodName(methodName);
        rpcRequest.setParamDesc(paramDesc);
        rpcRequest.setArguments(decodeRequestParameter(input, paramDesc, serialization));
        rpcRequest.setAttachments(decodeRequestAttachments(input));

        return rpcRequest;
    }

    private Object[] decodeRequestParameter(ObjectInput input, String parameterDesc, Serialization serialization)
            throws IOException, ClassNotFoundException {
        if (parameterDesc == null || parameterDesc.isEmpty()) {
            return null;
        }

        Class<?>[] classTypes = ReflectUtils.forNames(parameterDesc);

        Object[] paramObjs = new Object[classTypes.length];

        for (int i = 0; i < classTypes.length; i++) {
            paramObjs[i] = deserialize((byte[]) input.readObject(), classTypes[i], serialization);
        }

        return paramObjs;
    }

    private Map<String, String> decodeRequestAttachments(ObjectInput input) throws IOException {
        int size = input.readInt();

        if (size <= 0) {
            return null;
        }

        Map<String, String> attachments = new HashMap<>();

        for (int i = 0; i < size; i++) {
            attachments.put(input.readUTF(), input.readUTF());
        }

        return attachments;
    }

    private Object decodeResponse(ObjectInput input, byte dataType, long requestId, Serialization serialization)
            throws IOException, ClassNotFoundException {
        long processTime = input.readLong();

        DefaultResponse response = new DefaultResponse();
        response.setRequestId(requestId);
        response.setProcessTime(processTime);

        if (dataType == FLAG_RESPONSE_VOID) {
            return response;
        }

        String className = input.readUTF();
        Class<?> clazz = ReflectUtils.forName(className);

        Object result = deserialize((byte[]) input.readObject(), clazz, serialization);

        if (dataType == FLAG_RESPONSE) {
            response.setValue(result);
        } else if (dataType == FLAG_RESPONSE_EXCEPTION) {
            response.setException((Exception) result);
        } else {
            throw new JawsFrameworkException("decode error: response dataType not support " + dataType,
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        response.setRequestId(requestId);

        return response;
    }
}