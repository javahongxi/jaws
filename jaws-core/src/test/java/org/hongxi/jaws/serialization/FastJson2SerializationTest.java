package org.hongxi.jaws.serialization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FastJson2Serialization streaming API unit tests.
 */
class FastJson2SerializationTest {

    private Serialization serialization;

    @BeforeEach
    void setUp() {
        serialization = new FastJson2Serialization();
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
    void serializeNumberShouldReturnNonNullBytes() throws IOException {
        byte[] bytes = toBytes(42);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }

    @Test
    void deserializeNumberShouldReturnOriginalValue() throws Exception {
        byte[] bytes = toBytes(42);
        Integer result = fromBytes(bytes, Integer.class);
        assertEquals(42, result);
    }

    @Test
    void serializeStringRoundtrip() throws Exception {
        String original = "hello jaws";
        byte[] bytes = toBytes(original);
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
    void serializeNullShouldReturnBytes() throws IOException {
        byte[] bytes = toBytes(null);
        assertNotNull(bytes);
    }

    @Test
    void serializeEmptyStringRoundtrip() throws Exception {
        byte[] bytes = toBytes("");
        String result = fromBytes(bytes, String.class);
        assertEquals("", result);
    }

    @Test
    void serializationNumberShouldBeOne() {
        assertEquals(1, serialization.getSerializationNumber());
    }

    @Test
    void serializeRecordRoundtrip() throws Exception {
        /* fastjson2 JSONB has compatibility issues with record + List fields, so use a record without collection fields here */
        record SimpleRecord(String name, int value) implements Serializable {}

        SimpleRecord original = new SimpleRecord("record-test", 200);
        byte[] bytes = toBytes(original);
        SimpleRecord result = fromBytes(bytes, SimpleRecord.class);
        assertEquals(original, result);
    }

    @Test
    void serializeRecordWithNullFieldRoundtrip() throws Exception {
        record SimpleRecord(String name, int value) implements Serializable {}

        SimpleRecord original = new SimpleRecord(null, 0);
        byte[] bytes = toBytes(original);
        SimpleRecord result = fromBytes(bytes, SimpleRecord.class);
        assertEquals(original, result);
    }

    @Test
    void serializeNestedRecordRoundtrip() throws Exception {
        /* record nesting a record */
        record Inner(String msg) implements Serializable {}
        record Outer(String id, Inner inner) implements Serializable {}

        Outer original = new Outer("outer-1", new Inner("hello"));
        byte[] bytes = toBytes(original);
        Outer result = fromBytes(bytes, Outer.class);
        assertEquals(original, result);
    }

    @Test
    void securityFilterShouldBeAccessible() {
        FastJson2Serialization fjs = (FastJson2Serialization) serialization;
        assertNotNull(fjs.getSecurityFilter());
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
