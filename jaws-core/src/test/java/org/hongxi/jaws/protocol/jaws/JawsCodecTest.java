package org.hongxi.jaws.protocol.jaws;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JawsCodec 往返编解码单元测试。
 * <p>
 * 覆盖请求/响应的 encode → decode 往返一致性、各 flag 消息类型
 * （request / response / void / exception）、协议头校验失败路径，
 * 以及 serializationId 嵌入 flag 高 5 位后的提取与透传。
 */
class JawsCodecTest {

    private JawsCodec codec;
    private Channel channel;

    @BeforeEach
    void setUp() {
        codec = new JawsCodec();
        Map<String, String> params = new HashMap<>();
        params.put("serialization", "hessian2");
        channel = new FakeChannel(new URL("jaws", "127.0.0.1", 18001, "test", params));
    }

    // ---------- request round-trip ----------

    @Test
    void requestRoundtripWithArgsAndAttachments() throws IOException {
        DefaultRequest request = new DefaultRequest();
        request.setRequestId(42L);
        request.setInterfaceName("org.hongxi.jaws.FooService");
        request.setMethodName("hello");
        request.setParamDesc("java.lang.String,int");
        request.setArguments(new Object[]{"world", 100});
        Map<String, String> attachments = new HashMap<>();
        attachments.put("traceId", "t-123");
        attachments.put("token", "abc");
        request.setAttachments(attachments);

        ByteBuf buf = Unpooled.buffer();
        codec.encode(channel, request, buf);

        Object decoded = codec.decode(channel, buf);

        assertInstanceOf(Request.class, decoded);
        Request result = (Request) decoded;
        assertEquals(42L, result.getRequestId());
        assertEquals("org.hongxi.jaws.FooService", result.getInterfaceName());
        assertEquals("hello", result.getMethodName());
        assertEquals("java.lang.String,int", result.getParamDesc());
        assertArrayEquals(new Object[]{"world", 100}, result.getArguments());
        assertEquals(attachments, result.getAttachments());
        // serializationId embedded in flag high 5 bits survives the round-trip
        assertEquals(0, result.getSerializationNumber()); // hessian2 = 0
        // buffer fully consumed
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void requestRoundtripNoArgsNoAttachments() throws IOException {
        DefaultRequest request = new DefaultRequest();
        request.setRequestId(7L);
        request.setInterfaceName("org.hongxi.jaws.BarService");
        request.setMethodName("ping");
        request.setParamDesc("");
        request.setArguments(null);

        ByteBuf buf = Unpooled.buffer();
        codec.encode(channel, request, buf);
        Object decoded = codec.decode(channel, buf);

        assertInstanceOf(Request.class, decoded);
        Request result = (Request) decoded;
        assertTrue(result.getAttachments() == null || result.getAttachments().isEmpty());
        assertNull(result.getArguments());
        assertEquals(7L, result.getRequestId());
    }

    @Test
    void requestRoundtripPojoArgument() throws IOException {
        DefaultRequest request = new DefaultRequest();
        request.setRequestId(8L);
        request.setInterfaceName("org.hongxi.jaws.BazService");
        request.setMethodName("submit");
        request.setParamDesc("org.hongxi.jaws.protocol.jaws.JawsCodecTest$CodecPojo");
        request.setArguments(new Object[]{new CodecPojo("x", 9)});

        ByteBuf buf = Unpooled.buffer();
        codec.encode(channel, request, buf);
        Object decoded = codec.decode(channel, buf);

        Request result = (Request) decoded;
        assertEquals(1, result.getArguments().length);
        assertEquals(new CodecPojo("x", 9), result.getArguments()[0]);
    }

    // ---------- response round-trips ----------

    @Test
    void responseWithValueRoundtrip() throws IOException {
        DefaultResponse response = new DefaultResponse();
        response.setRequestId(42L);
        response.setValue("result-payload");
        response.setProcessTime(123L);
        response.setSerializationNumber((byte) 0);

        ByteBuf buf = Unpooled.buffer();
        codec.encode(channel, response, buf);
        Object decoded = codec.decode(channel, buf);

        assertInstanceOf(Response.class, decoded);
        Response result = (Response) decoded;
        assertEquals(42L, result.getRequestId());
        assertEquals("result-payload", result.getValue());
        assertNull(result.getException());
        assertEquals(123L, result.getProcessTime());
    }

    @Test
    void voidResponseRoundtrip() throws IOException {
        DefaultResponse response = new DefaultResponse();
        response.setRequestId(43L);
        response.setValue(null);
        response.setSerializationNumber((byte) 0);

        ByteBuf buf = Unpooled.buffer();
        codec.encode(channel, response, buf);
        Object decoded = codec.decode(channel, buf);

        Response result = (Response) decoded;
        assertEquals(43L, result.getRequestId());
        assertNull(result.getValue());
        assertNull(result.getException());
    }

    @Test
    void exceptionResponseRoundtrip() throws IOException {
        DefaultResponse response = new DefaultResponse();
        response.setRequestId(44L);
        response.setException(new IllegalStateException("boom"));
        response.setSerializationNumber((byte) 0);

        ByteBuf buf = Unpooled.buffer();
        codec.encode(channel, response, buf);
        Object decoded = codec.decode(channel, buf);

        Response result = (Response) decoded;
        assertEquals(44L, result.getRequestId());
        assertNotNull(result.getException());
        assertEquals(IllegalStateException.class, result.getException().getClass());
        assertEquals("boom", result.getException().getMessage());
    }

    @Test
    void responseSerializationFollowsRequest() throws IOException {
        // fastjson2 = number 1: response encoded with fastjson2 carries id 1 in flag
        DefaultResponse response = new DefaultResponse();
        response.setRequestId(45L);
        response.setValue("payload");
        response.setSerializationNumber((byte) 1);

        ByteBuf buf = Unpooled.buffer();
        codec.encode(channel, response, buf);
        Object decoded = codec.decode(channel, buf);

        Response result = (Response) decoded;
        assertEquals("payload", result.getValue());
        assertEquals(1, result.getSerializationNumber());
    }

    // ---------- header validation failures ----------

    @Test
    void decodeRejectsUnknownSerializationId() {
        // craft a valid-looking frame with serializationId = 31 (unassigned)
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(JawsCodec.MAGIC);
        buf.writeByte(JawsCodec.VERSION);
        buf.writeByte((byte) (JawsCodec.FLAG_REQUEST | (31 << 3)));
        buf.writeLong(1L);
        buf.writeInt(8);
        buf.writeBytes(new byte[8]); // some body so the frame passes the length check

        JawsFrameworkException ex = assertThrows(JawsFrameworkException.class,
                () -> codec.decode(channel, buf));
        assertTrue(ex.getMessage().contains("unknown serializationId"));
        // regression guard: exception must be thrown without retaining any slice
        // (buffer is test-owned and unreleased, but the code path must not retain)
        assertEquals(1, buf.refCnt());
    }

    @Test
    void decodeRejectsBadMagic() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort((short) 0xBAD0);
        buf.writeByte(JawsCodec.VERSION);
        buf.writeByte(JawsCodec.FLAG_REQUEST);
        buf.writeLong(1L);
        buf.writeInt(0);
        buf.writeBytes(new byte[16]);

        assertThrows(JawsFrameworkException.class, () -> codec.decode(channel, buf));
    }

    @Test
    void decodeRejectsBadVersion() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(JawsCodec.MAGIC);
        buf.writeByte((byte) 99);
        buf.writeByte(JawsCodec.FLAG_REQUEST);
        buf.writeLong(1L);
        buf.writeInt(0);
        buf.writeBytes(new byte[16]);

        assertThrows(JawsFrameworkException.class, () -> codec.decode(channel, buf));
    }

    @Test
    void decodeRejectsContentLengthMismatch() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(JawsCodec.MAGIC);
        buf.writeByte(JawsCodec.VERSION);
        buf.writeByte(JawsCodec.FLAG_REQUEST);
        buf.writeLong(1L);
        buf.writeInt(100); // claims 100 bytes of body...
        buf.writeBytes(new byte[10]); // ...but only 10 present

        assertThrows(JawsFrameworkException.class, () -> codec.decode(channel, buf));
    }

    @Test
    void encodeRejectsUnsupportedMessageType() {
        ByteBuf buf = Unpooled.buffer();
        assertThrows(JawsFrameworkException.class, () -> codec.encode(channel, "not-a-message", buf));
    }

    // ---------- heartbeat ----------

    @Test
    void encodeHeartbeatProducesValidFrame() {
        ByteBuf buf = Unpooled.buffer();
        codec.encodeHeartbeat(buf);

        assertEquals(JawsCodec.HEADER_LENGTH, buf.readableBytes());
        assertEquals(JawsCodec.MAGIC, buf.readShort());
        assertEquals(JawsCodec.VERSION, buf.readByte());
        assertEquals(JawsCodec.FLAG_EVENT, buf.readByte());
        assertEquals(0L, buf.readLong());   // requestId
        assertEquals(0, buf.readInt());     // body length
        buf.release();
    }

    // ---------- helpers ----------

    /** Minimal fake transport channel backed by a URL with serialization params. */
    private record FakeChannel(URL url) implements Channel {

        @Override
        public boolean open() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public void close(int timeout) {
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public URL getUrl() {
            return url;
        }
    }

    /** Simple POJO argument for round-trip tests. */
    static class CodecPojo implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final int value;

        CodecPojo(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CodecPojo pojo = (CodecPojo) o;
            return value == pojo.value && Objects.equals(name, pojo.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }
    }
}
