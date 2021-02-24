package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.codec.AbstractCodec;
import org.hongxi.jaws.codec.Serialization;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.ByteUtils;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.transport.Channel;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by shenhongxi on 2020/7/25.
 */
@SpiMeta(name = "jaws")
public class JawsCodec extends AbstractCodec {

    public static final short MAGIC = (short) 0xF0F0;

    public static final byte MASK = 0x07;

    public static final int HEADER_LENGTH = 16;

    public static final byte VERSION = 1;

    @Override
    public byte[] encode(Channel channel, Object message) throws IOException {
        try {
            if (message instanceof Request) {
                return encodeRequest(channel, (Request) message);
            } else if (message instanceof Response) {
                return encodeResponse(channel, (Response) message);
            }
        } catch (Exception e) {
            if (ExceptionUtils.isJawsException(e)) {
                throw (RuntimeException) e;
            } else {
                throw new JawsFrameworkException("encode error: isResponse=" + (message instanceof Response), e,
                        JawsErrorMsgConstants.FRAMEWORK_ENCODE_ERROR);
            }
        }

        throw new JawsFrameworkException("encode error: message type not support, " + message.getClass(),
                JawsErrorMsgConstants.FRAMEWORK_ENCODE_ERROR);
    }

    /**
     * decode data
     *
     * <pre>
     * 		对于client端：主要是来自server端的response or exception
     * 		对于server端: 主要是来自client端的request
     * </pre>
     *
     * @param data
     * @return
     * @throws IOException
     */
    @Override
    public Object decode(Channel channel, String remoteIp, byte[] data) throws IOException {
        if (data.length <= HEADER_LENGTH) {
            throw new JawsFrameworkException("decode error: format problem",
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        short type = ByteUtils.bytes2short(data, 0);

        if (type != MAGIC) {
            throw new JawsFrameworkException("decode error: magic error",
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        if (data[2] != VERSION) {
            throw new JawsFrameworkException("decode error: version error",
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        int bodyLength = ByteUtils.bytes2int(data, 12);

        if (HEADER_LENGTH + bodyLength != data.length) {
            throw new JawsFrameworkException("decode error: content length error",
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        byte flag = data[3];
        byte dataType = (byte) (flag & MASK);
        boolean isResponse = (dataType != JawsConstants.FLAG_REQUEST);

        byte[] body = new byte[bodyLength];

        System.arraycopy(data, HEADER_LENGTH, body, 0, bodyLength);

        long requestId = ByteUtils.bytes2long(data, 4);
        Serialization serialization = ExtensionLoader.getExtensionLoader(Serialization.class)
                .getExtension(channel.getUrl().getParameter(URLParamType.serialization.getName(),
                        URLParamType.serialization.value()));

        try {
            if (isResponse) { // response
                return decodeResponse(body, dataType, requestId, serialization);
            } else {
                return decodeRequest(body, requestId, serialization);
            }
        } catch (ClassNotFoundException e) {
            throw new JawsFrameworkException("decode " + (isResponse ? "response" : "request") +
                    " error: class not found", e,
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        } catch (Exception e) {
            if (ExceptionUtils.isJawsException(e)) {
                throw (RuntimeException) e;
            } else {
                throw new JawsFrameworkException("decode error: isResponse=" + isResponse,
                        e, JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
            }
        }
    }

    /**
     * request body 数据：
     *
     * <pre>
     *
     * 	 body:
     *
     * 	 byte[] data :
     *
     * 			serialize(interface_name, method_name, method_param_desc, method_param_value, attachments_size, attachments_value)
     *
     *   method_param_desc:  for_each (string.append(method_param_interface_name))
     *
     *   method_param_value: for_each (method_param_name, method_param_value)
     *
     * 	 attachments_value:  for_each (attachment_name, attachment_value)
     *
     * </pre>
     *
     * @param request
     * @return
     * @throws IOException
     */
    private byte[] encodeRequest(Channel channel, Request request) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutput output = createOutput(outputStream);
        output.writeUTF(request.getInterfaceName());
        output.writeUTF(request.getMethodName());
        output.writeUTF(request.getParametersDesc());

        Serialization serialization =
                ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(
                        channel.getUrl().getParameter(URLParamType.serialization.getName(),
                                URLParamType.serialization.value()));

        if (request.getArguments() != null && request.getArguments().length > 0) {
            for (Object obj : request.getArguments()) {
                serialize(output, obj, serialization);
            }
        }

        if (request.getAttachments() == null || request.getAttachments().isEmpty()) {
            // empty attachments
            output.writeInt(0);
        } else {
            output.writeInt(request.getAttachments().size());
            for (Map.Entry<String, String> entry : request.getAttachments().entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeUTF(entry.getValue());
            }
        }

        output.flush();
        byte[] body = outputStream.toByteArray();

        byte flag = JawsConstants.FLAG_REQUEST;

        output.close();

        return encode(body, flag, request.getRequestId());
    }

    /**
     * response body 数据：
     *
     * <pre>
     *
     * body:
     *
     * 	 byte[] :  serialize (result) or serialize (exception)
     *
     * </pre>
     *
     * @param channel
     * @param value
     * @return
     * @throws IOException
     */
    private byte[] encodeResponse(Channel channel, Response value) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutput output = createOutput(outputStream);
        Serialization serialization =
                ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(
                        channel.getUrl().getParameter(URLParamType.serialization.getName(),
                                URLParamType.serialization.value()));

        byte flag = 0;

        output.writeLong(value.getProcessTime());

        if (value.getException() != null) {
            output.writeUTF(value.getException().getClass().getName());
            serialize(output, value.getException(), serialization);
            flag = JawsConstants.FLAG_RESPONSE_EXCEPTION;
        } else if (value.getValue() == null) {
            flag = JawsConstants.FLAG_RESPONSE_VOID;
        } else {
            output.writeUTF(value.getValue().getClass().getName());
            serialize(output, value.getValue(), serialization);
            flag = JawsConstants.FLAG_RESPONSE;
        }

        output.flush();

        byte[] body = outputStream.toByteArray();

        output.close();

        return encode(body, flag, value.getRequestId());
    }

    /**
     * 数据协议：
     *
     * <pre>
     *
     * header:  16个字节
     *
     * 0-15 bit 	:  magic
     * 16-23 bit	:  version
     * 24-31 bit	:  extend flag , 其中： 29-30 bit: event 可支持4种event，比如normal, exception等,  31 bit : 0 is request , 1 is response
     * 32-95 bit 	:  request id
     * 96-127 bit 	:  body content length
     *
     * </pre>
     *
     * @param body
     * @param flag
     * @param requestId
     * @return
     * @throws IOException
     */
    private byte[] encode(byte[] body, byte flag, long requestId) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        int offset = 0;

        // 0 - 15 bit : magic
        ByteUtils.short2bytes(MAGIC, header, offset);
        offset += 2;

        // 16 - 23 bit : version
        header[offset++] = VERSION;

        // 24 - 31 bit : extend flag
        header[offset++] = flag;

        // 32 - 95 bit : requestId
        ByteUtils.long2bytes(requestId, header, offset);
        offset += 8;

        // 96 - 127 bit : body content length
        ByteUtils.int2bytes(body.length, header, offset);

        byte[] data = new byte[header.length + body.length];

        System.arraycopy(header, 0, data, 0, header.length);
        System.arraycopy(body, 0, data, header.length, body.length);

        return data;
    }

    private Object decodeRequest(byte[] body, long requestId, Serialization serialization) throws IOException, ClassNotFoundException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
        ObjectInput input = createInput(inputStream);

        String interfaceName = input.readUTF();
        String methodName = input.readUTF();
        String paramtersDesc = input.readUTF();

        DefaultRequest rpcRequest = new DefaultRequest();
        rpcRequest.setRequestId(requestId);
        rpcRequest.setInterfaceName(interfaceName);
        rpcRequest.setMethodName(methodName);
        rpcRequest.setParametersDesc(paramtersDesc);
        rpcRequest.setArguments(decodeRequestParameter(input, paramtersDesc, serialization));
        rpcRequest.setAttachments(decodeRequestAttachments(input));

        input.close();

        return rpcRequest;
    }

    private Object[] decodeRequestParameter(ObjectInput input, String parameterDesc, Serialization serialization) throws IOException,
            ClassNotFoundException {
        if (parameterDesc == null || parameterDesc.equals("")) {
            return null;
        }

        Class<?>[] classTypes = ReflectUtils.forNames(parameterDesc);

        Object[] paramObjs = new Object[classTypes.length];

        for (int i = 0; i < classTypes.length; i++) {
            paramObjs[i] = deserialize((byte[]) input.readObject(), classTypes[i], serialization);
        }

        return paramObjs;
    }

    private Map<String, String> decodeRequestAttachments(ObjectInput input) throws IOException, ClassNotFoundException {
        int size = input.readInt();

        if (size <= 0) {
            return null;
        }

        Map<String, String> attachments = new HashMap<String, String>();

        for (int i = 0; i < size; i++) {
            attachments.put(input.readUTF(), input.readUTF());
        }

        return attachments;
    }

    private Object decodeResponse(byte[] body, byte dataType, long requestId, Serialization serialization) throws IOException,
            ClassNotFoundException {

        ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
        ObjectInput input = createInput(inputStream);

        long processTime = input.readLong();

        DefaultResponse response = new DefaultResponse();
        response.setRequestId(requestId);
        response.setProcessTime(processTime);

        if (dataType == JawsConstants.FLAG_RESPONSE_VOID) {
            return response;
        }

        String className = input.readUTF();
        Class<?> clz = ReflectUtils.forName(className);

        Object result = deserialize((byte[]) input.readObject(), clz, serialization);

        if (dataType == JawsConstants.FLAG_RESPONSE) {
            response.setValue(result);
        } else if (dataType == JawsConstants.FLAG_RESPONSE_EXCEPTION) {
            response.setException((Exception) result);
        } else {
            throw new JawsFrameworkException("decode error: response dataType not support " + dataType,
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }

        response.setRequestId(requestId);

        input.close();

        return response;
    }
}
