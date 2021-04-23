package org.hongxi.jaws.codec;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.protocol.jaws.JawsCodec;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.common.util.ByteUtils;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public class CodecUtils {
    private static final Logger logger = LoggerFactory.getLogger(CodecUtils.class);

    public static byte[] encodeObjectToBytes(Channel channel, Codec codec, Object msg) {
        try {
            byte[] data = encodeMessage(channel, codec, msg);
            short type = ByteUtils.bytes2short(data, 0);
            if (type == JawsCodec.MAGIC) {
                return encodeV1(msg, data);
            } else {
                throw new JawsFrameworkException("can not encode message, unknown magic:" + type);
            }
        } catch (IOException e) {
            throw new JawsFrameworkException("encode error: isResponse=" + (msg instanceof Response), e,
                    JawsErrorMsgConstants.FRAMEWORK_ENCODE_ERROR);
        }
    }

    private static byte[] encodeV1(Object msg, byte[] data) throws IOException {
        long requestId = getRequestId(msg);
        byte[] result = new byte[JawsCodec.HEADER_LENGTH + data.length];
        ByteUtils.short2bytes(JawsConstants.NETTY_MAGIC_TYPE, result, 0);
        result[3] = getType(msg);
        ByteUtils.long2bytes(requestId, result, 4);
        ByteUtils.int2bytes(data.length, result, 12);
        System.arraycopy(data, 0, result, JawsCodec.HEADER_LENGTH, data.length);
        return result;
    }

    private static byte[] encodeMessage(Channel channel, Codec codec, Object msg) throws IOException {
        byte[] data;
        if (msg instanceof Response) {
            try {
                data = codec.encode(channel, msg);
            } catch (Exception e) {
                logger.error("NettyEncoder encode error, identity=" + channel.getUrl().getIdentity(), e);
                Response oriResponse = (Response) msg;
                Response response = JawsFrameworkUtils.buildErrorResponse(oriResponse.getRequestId(), e);
                data = codec.encode(channel, response);
            }
        } else {
            data = codec.encode(channel, msg);
        }
        return data;
    }

    private static long getRequestId(Object message) {
        if (message instanceof Request) {
            return ((Request) message).getRequestId();
        } else if (message instanceof Response) {
            return ((Response) message).getRequestId();
        } else {
            return 0;
        }
    }

    private static byte getType(Object message) {
        if (message instanceof Request) {
            return JawsConstants.FLAG_REQUEST;
        } else if (message instanceof Response) {
            return JawsConstants.FLAG_RESPONSE;
        } else {
            return JawsConstants.FLAG_OTHER;
        }
    }
}
