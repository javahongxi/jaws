package org.hongxi.jaws.serialization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProtostuffSerialization streaming API unit tests.
 * <p>
 * Note: protostuff requires an accessible no-arg constructor, so record /
 * constructor-only classes covered by the hessian2 tests are excluded here.
 */
class ProtostuffSerializationTest {

    private Serialization serialization;

    @BeforeEach
    void setUp() {
        serialization = new ProtostuffSerialization();
    }

    private byte[] toBytes(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = serialization.serialize(bos);
        out.writeObject(obj);
        out.flush();
        return bos.toByteArray();
    }

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
    void serializeEmptyStringRoundtrip() throws Exception {
        byte[] bytes = toBytes("");
        String result = fromBytes(bytes, String.class);
        assertEquals("", result);
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
        // protostuff omits default values on the wire, null fields are restored as defaults
        TestPojo original = new TestPojo(null, 0, null);
        byte[] bytes = toBytes(original);
        TestPojo result = fromBytes(bytes, TestPojo.class);
        assertEquals(original, result);
    }

    @Test
    void serializeNullObjectRoundtrip() throws Exception {
        byte[] bytes = toBytes(null);
        TestPojo result = fromBytes(bytes, TestPojo.class);
        assertNull(result);
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
    void serializePojoListRoundtrip() throws Exception {
        List<TestPojo> original = new ArrayList<>(List.of(
                new TestPojo("a", 1, List.of("t")),
                new TestPojo("b", 2, null)));
        byte[] bytes = toBytes(original);
        List<TestPojo> result = fromBytes(bytes, List.class);
        assertEquals(original, result);
    }

    @Test
    void serializeMapRoundtrip() throws Exception {
        Map<String, TestPojo> original = new HashMap<>();
        original.put("k1", new TestPojo("a", 1, null));
        original.put("k2", null);
        byte[] bytes = toBytes(original);
        Map<String, TestPojo> result = fromBytes(bytes, Map.class);
        assertEquals(original, result);
    }

    @Test
    void serializeListWithNullElementRoundtrip() throws Exception {
        List<Object> original = new ArrayList<>();
        original.add("x");
        original.add(null);
        original.add(42);
        byte[] bytes = toBytes(original);
        List<Object> result = fromBytes(bytes, List.class);
        assertEquals(original, result);
    }

    @Test
    void serializationNumberShouldBeTwo() {
        assertEquals(2, serialization.getSerializationNumber());
    }

    @Test
    void serializedBytesShouldDifferForDifferentObjects() throws Exception {
        byte[] bytes1 = toBytes("hello");
        byte[] bytes2 = toBytes("world");
        assertFalse(java.util.Arrays.equals(bytes1, bytes2));
    }

    @Test
    void readObjectWithoutClassShouldUseEmbeddedClassName() throws Exception {
        TestPojo original = new TestPojo("typed", 42, List.of("k"));
        byte[] bytes = toBytes(original);
        ObjectInput in = serialization.deserialize(new ByteArrayInputStream(bytes));
        Object result = in.readObject();
        assertInstanceOf(TestPojo.class, result);
        assertEquals(original, result);
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
