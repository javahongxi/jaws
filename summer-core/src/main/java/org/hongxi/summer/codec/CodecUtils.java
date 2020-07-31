package org.hongxi.summer.codec;

import org.hongxi.summer.exception.SummerErrorMsgConstants;
import org.hongxi.summer.exception.SummerFrameworkException;
import org.hongxi.summer.protocol.summer.SummerHeader;
import org.hongxi.summer.rpc.Response;
import org.hongxi.summer.transport.Channel;
import org.hongxi.summer.util.ByteUtils;
import org.hongxi.summer.util.SummerFrameworkUtils;
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
            if (type == SummerHeader.MAGIC) {
                return data;
            } else {
                throw new SummerFrameworkException("can not encode message, unknown magic:" + type);
            }
        } catch (IOException e) {
            throw new SummerFrameworkException("encode error: isResponse=" + (msg instanceof Response),
                    e, SummerErrorMsgConstants.FRAMEWORK_ENCODE_ERROR);
        }
    }

    private static byte[] encodeMessage(Channel channel, Codec codec, Object msg) throws IOException {
        byte[] data;
        if (msg instanceof Response) {
            try {
                data = codec.encode(channel, msg);
            } catch (Exception e) {
                logger.error("encode error, identity={}", channel.getUrl().getIdentity(), e);
                Response oriResponse = (Response) msg;
                Response response = SummerFrameworkUtils.buildErrorResponse(oriResponse.getRequestId(), e);
                data = codec.encode(channel, response);
            }
        } else {
            data = codec.encode(channel, msg);
        }
        return data;
    }
}
