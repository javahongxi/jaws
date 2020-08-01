package org.hongxi.summer.protocol.summer;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.summer.codec.AbstractCodec;
import org.hongxi.summer.codec.Serialization;
import org.hongxi.summer.common.URLParamType;
import org.hongxi.summer.common.extension.ExtensionLoader;
import org.hongxi.summer.common.extension.SpiMeta;
import org.hongxi.summer.exception.SummerErrorMsgConstants;
import org.hongxi.summer.exception.SummerFrameworkException;
import org.hongxi.summer.exception.SummerServiceException;
import org.hongxi.summer.rpc.DefaultRequest;
import org.hongxi.summer.rpc.DefaultResponse;
import org.hongxi.summer.rpc.Request;
import org.hongxi.summer.rpc.Response;
import org.hongxi.summer.serialize.DeserializableObject;
import org.hongxi.summer.transport.Channel;
import org.hongxi.summer.common.util.ByteUtils;
import org.hongxi.summer.common.util.ExceptionUtils;
import org.hongxi.summer.common.util.MathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.hongxi.summer.common.SummerConstants.*;

/**
 * Created by shenhongxi on 2020/7/25.
 */
@SpiMeta(name = "summer")
public class SummerCodec extends AbstractCodec {
    private static final Logger logger = LoggerFactory.getLogger(SummerCodec.class);

    private static final byte MASK = 0x07;
    private static final int HEADER_SIZE = 13;

    static {
        initAllSerialization();
    }

    @Override
    public byte[] encode(Channel channel, Object message) throws IOException {
        try {
            return encode0(channel, message);
        } catch (Exception e) {
            String errmsg = "";
            if (message != null) {
                if (message instanceof Request) {
                    errmsg = "type:request, " + message.toString();
                } else {
                    errmsg = "type:response, " + message.toString();
                }
            }
            logger.warn("summer encode error, {}", errmsg, e);
            if (ExceptionUtils.isSummerException(e)) {
                throw (RuntimeException) e;
            } else {
                throw new SummerFrameworkException("encode error!" + errmsg + ", origin errmsg:" + e.getMessage(), e,
                        SummerErrorMsgConstants.FRAMEWORK_ENCODE_ERROR);
            }
        }
    }

    public byte[] encode0(Channel channel, Object message) throws IOException {
        SummerHeader header = new SummerHeader();
        byte[] body = null;
        Serialization serialization;
        GrowableByteBuffer buf = new GrowableByteBuffer(4096);
        // meta
        int index = HEADER_SIZE;
        buf.position(index);
        buf.putInt(0); // metasize

        if (message instanceof Request) {
            String serializationName = channel.getUrl().getParameter(
                    URLParamType.serialization.getName(), URLParamType.serialization.value());
            serialization = ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(serializationName);
            if (serialization == null) {
                throw new SummerServiceException("can not find serialization " + serializationName);
            }
            header.setSerializationNumber(serialization.getSerializationNumber());
            Request request = (Request) message;
            putString(buf, SUMMER_PATH);
            putString(buf, request.getInterfaceName());
            putString(buf, SUMMER_METHOD);
            putString(buf, request.getMethodName());
            if (request.getParametersDesc() != null) {
                putString(buf, SUMMER_METHOD_DESC);
                putString(buf, request.getParametersDesc());
            }
            if (request.getAttachments() != null && request.getAttachments().get(URLParamType.group.getName()) != null) {
                request.setAttachment(SUMMER_GROUP, request.getAttachments().get(URLParamType.group.getName()));
            }

            putMap(buf, request.getAttachments());

            header.setRequestId(request.getRequestId());
            if (request.getArguments() != null) {
                body = serialization.serializeMulti(request.getArguments());
            }
        } else if (message instanceof Response) {
            Response response = (Response) message;
            serialization = getSerializationByNum(response.getSerializationNumber());
            header.setSerializationNumber(serialization.getSerializationNumber());

            putString(buf, SUMMER_PROCESS_TIME);
            putString(buf, String.valueOf(response.getProcessTime()));
            if (response.getException() != null) {
                putString(buf, SUMMER_ERROR);
                putString(buf, ExceptionUtils.toMessage(response.getException()));
                header.setStatus(SummerHeader.MessageStatus.EXCEPTION.status());
            }
            putMap(buf, response.getAttachments());

            header.setRequestId(response.getRequestId());
            header.setRequest(false);
            if (response.getException() == null) {
                body = serialization.serialize(response.getValue());
            }
        }

        buf.position(buf.position() - 1);
        int metaLength = buf.position() - index - 4;
        buf.putInt(index, metaLength);

        //body
        if (body != null && body.length > 0) {
            if (channel.getUrl().getBooleanParameter(URLParamType.gzip.getName(), URLParamType.gzip.boolValue())
                    && body.length > channel.getUrl().getIntParameter(URLParamType.minGzipSize.getName(), URLParamType.minGzipSize.intValue())) {
                try {
                    body = ByteUtils.gzip(body);
                    header.setGzip(true);
                } catch (IOException e) {
                    logger.warn("encode gzip fail. so not gzip body.", e);
                }
            }
            buf.putInt(body.length);
            buf.put(body);
        } else {
            buf.putInt(0);
        }

        //header
        int position = buf.position();
        buf.position(0);
        buf.put(header.toBytes());
        buf.position(position);
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    @Override
    public Object decode(Channel channel, String remoteIp, byte[] data) throws IOException {
        SummerHeader header = SummerHeader.buildHeader(data);
        Map<String, String> metaMap = new HashMap<>();
        ByteBuffer buf = ByteBuffer.wrap(data);
        int metaSize = buf.getInt(HEADER_SIZE);
        int index = HEADER_SIZE + 4;
        if (metaSize > 0) {
            byte[] meta = new byte[metaSize];
            buf.position(index);
            buf.get(meta);
            metaMap = decodeMeta(meta);
            index += metaSize;
        }
        int bodySize = buf.getInt(index);
        index += 4;
        Object obj = null;
        if (bodySize > 0) {
            byte[] body = new byte[bodySize];
            buf.position(index);
            buf.get(body);
            if (header.isGzip()) {
                body = ByteUtils.unGzip(body);
            }
            //默认自适应序列化
            Serialization serialization = getSerializationByNum(header.getSerializationNumber());
            obj = new DeserializableObject(serialization, body);
        }
        
        if (header.isRequest()) {
            DefaultRequest request = new DefaultRequest();
            request.setRequestId(header.getRequestId());
            request.setInterfaceName(metaMap.remove(SUMMER_PATH));
            request.setMethodName(metaMap.remove(SUMMER_METHOD));
            request.setParametersDesc(metaMap.remove(SUMMER_METHOD_DESC));
            request.setAttachments(metaMap);
            request.setSerializationNumber(header.getSerializationNumber());
            if (obj != null) {
                request.setArguments(new Object[]{obj});
            }
            if (metaMap.get(SUMMER_GROUP) != null) {
                request.setAttachment(URLParamType.group.getName(), metaMap.get(SUMMER_GROUP));
            }

            if (StringUtils.isNotBlank(metaMap.get(SUMMER_VERSION))) {
                request.setAttachment(URLParamType.version.getName(), metaMap.get(SUMMER_VERSION));
            }

            if (StringUtils.isNotBlank(metaMap.get(SUMMER_SOURCE))) {
                request.setAttachment(URLParamType.application.getName(), metaMap.get(SUMMER_SOURCE));
            }

            if (StringUtils.isNotBlank(metaMap.get(SUMMER_MODULE))) {
                request.setAttachment(URLParamType.module.getName(), metaMap.get(SUMMER_MODULE));
            }
            
            return request;
        } else {
            DefaultResponse response = new DefaultResponse();
            response.setRequestId(header.getRequestId());
            response.setProcessTime(MathUtils.parseLong(metaMap.remove(SUMMER_PROCESS_TIME), 0));
            response.setAttachments(metaMap);
            if (header.getStatus() == SummerHeader.MessageStatus.NORMAL.status()) {//只解析正常消息
                response.setValue(obj);
            } else {
                String errmsg = metaMap.remove(SUMMER_ERROR);
                Exception e = ExceptionUtils.fromMessage(errmsg);
                if (e == null) {
                    e = new SummerServiceException("default remote exception. remote errmsg:" + errmsg);
                }
                response.setException(e);
            }
            return response;
        }
    }

    private void putMap(GrowableByteBuffer buf, Map<String, String> map) throws UnsupportedEncodingException {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            putString(buf, entry.getKey());
            putString(buf, entry.getValue());
        }
    }

    private void putString(GrowableByteBuffer buf, String content) throws UnsupportedEncodingException {
        buf.put(content.getBytes("UTF-8"));
        buf.put("\n".getBytes("UTF-8"));
    }

    private Map<String, String> decodeMeta(byte[] meta) {
        Map<String, String> map = new HashMap<String, String>();
        if (meta != null && meta.length > 0) {
            String[] s = new String(meta).split("\n");
            for (int i = 0; i < s.length - 1; i++) {
                map.put(s[i++], s[i]);
            }
        }
        return map;
    }
}
