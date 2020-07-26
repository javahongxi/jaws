package org.hongxi.summer.protocol.summer;

import org.hongxi.summer.codec.AbstractCodec;
import org.hongxi.summer.codec.Serialization;
import org.hongxi.summer.common.URLParamType;
import org.hongxi.summer.core.extension.ExtensionLoader;
import org.hongxi.summer.core.extension.SpiMeta;
import org.hongxi.summer.exception.SummerErrorMsgConstants;
import org.hongxi.summer.exception.SummerFrameworkException;
import org.hongxi.summer.exception.SummerServiceException;
import org.hongxi.summer.rpc.Request;
import org.hongxi.summer.rpc.Response;
import org.hongxi.summer.transport.Channel;
import org.hongxi.summer.util.ByteUtils;
import org.hongxi.summer.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
    public Object decode(Channel channel, String remoteIp, byte[] buffer) throws IOException {
        return null;
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
}
