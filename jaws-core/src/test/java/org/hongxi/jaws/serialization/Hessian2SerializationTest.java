package org.hongxi.jaws.serialization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hessian2Serialization streaming API unit tests.
 */
class Hessian2SerializationTest {

    private Serialization serialization;

    @BeforeEach
    void setUp() {
        serialization = new Hessian2Serialization();
    }

    private byte[] toBytes(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = serialization.serialize(bos);
        out.writeObject(obj);
        out.flush();
        return bos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private <T> T fromBytes(byte[] bytes, Class<T> clazz) throws Exception {
        ObjectInput in = serialization.deserialize(new ByteArrayInputStream(bytes));
        return in.readObject(clazz);
    }

    @Test
    void serializeStringRoundtrip() throws Exception {
        String original = "hello jaws";
        byte[] bytes = toBytes(original);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        String result = fromBytes(bytes, String.class);
        assertEquals(original, result);
    }

    @Test
    void serializePojoRoundtrip() throws Exception {
        TestPojo original = new TestPojo("test", 100, List.of("a", "b", "c"));
        byte[] bytes = toBytes(original);
        TestPojo result = fromBytes(bytes, TestPojo.class);
        assertEquals(original, result);
    }

    @Test
    void serializePojoWithNullFieldsRoundtrip() throws Exception {
        TestPojo original = new TestPojo(null, 0, null);
        byte[] bytes = toBytes(original);
        TestPojo result = fromBytes(bytes, TestPojo.class);
        assertEquals(original, result);
    }

    @Test
    void serializeIntegerRoundtrip() throws Exception {
        byte[] bytes = toBytes(999);
        Integer result = fromBytes(bytes, Integer.class);
        assertEquals(999, result);
    }

    @Test
    void serializeLongRoundtrip() throws Exception {
        byte[] bytes = toBytes(123456789L);
        Long result = fromBytes(bytes, Long.class);
        assertEquals(123456789L, result);
    }

    @Test
    void serializeBooleanRoundtrip() throws Exception {
        byte[] bytes = toBytes(true);
        Boolean result = fromBytes(bytes, Boolean.class);
        assertTrue(result);
    }

    @Test
    void serializeEmptyStringRoundtrip() throws Exception {
        byte[] bytes = toBytes("");
        String result = fromBytes(bytes, String.class);
        assertEquals("", result);
    }

    @Test
    void serializationNumberShouldBeZero() {
        assertEquals(0, serialization.getSerializationNumber());
    }

    @Test
    void serializeRecordRoundtrip() throws Exception {
        TestRecord original = new TestRecord("record-test", 200, List.of("x", "y"));
        byte[] bytes = toBytes(original);
        TestRecord result = fromBytes(bytes, TestRecord.class);
        assertEquals(original, result);
    }

    @Test
    void serializeRecordWithNullFieldsRoundtrip() throws Exception {
        TestRecord original = new TestRecord(null, 0, null);
        byte[] bytes = toBytes(original);
        TestRecord result = fromBytes(bytes, TestRecord.class);
        assertEquals(original, result);
    }

    @Test
    void serializedBytesShouldDifferForDifferentObjects() throws Exception {
        byte[] bytes1 = toBytes("hello");
        byte[] bytes2 = toBytes("world");
        assertFalse(java.util.Arrays.equals(bytes1, bytes2));
    }

    @Test
    void streamingMultipleFieldsRoundtrip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = serialization.serialize(bos);
        out.writeUTF("methodName");
        out.writeInt(42);
        out.writeLong(999L);
        out.writeObject("arg1");
        out.flush();
        out.close();

        ObjectInput in = serialization.deserialize(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals("methodName", in.readUTF());
        assertEquals(42, in.readInt());
        assertEquals(999L, in.readLong());
        assertEquals("arg1", in.readObject());
    }
}
