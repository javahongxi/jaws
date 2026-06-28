package org.hongxi.jaws.serialize;

import org.hongxi.jaws.codec.Serialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hessian2Serialization 单元测试
 */
class Hessian2SerializationTest {

    private Serialization serialization;

    @BeforeEach
    void setUp() {
        serialization = new Hessian2Serialization();
    }

    @Test
    void serializeStringRoundtrip() throws IOException {
        String original = "hello jaws";
        byte[] bytes = serialization.serialize(original);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        String result = serialization.deserialize(bytes, String.class);
        assertEquals(original, result);
    }

    @Test
    void serializePojoRoundtrip() throws IOException {
        TestPojo original = new TestPojo("test", 100, List.of("a", "b", "c"));
        byte[] bytes = serialization.serialize(original);
        TestPojo result = serialization.deserialize(bytes, TestPojo.class);
        assertEquals(original, result);
    }

    @Test
    void serializePojoWithNullFieldsRoundtrip() throws IOException {
        TestPojo original = new TestPojo(null, 0, null);
        byte[] bytes = serialization.serialize(original);
        TestPojo result = serialization.deserialize(bytes, TestPojo.class);
        assertEquals(original, result);
    }

    @Test
    void serializeIntegerRoundtrip() throws IOException {
        byte[] bytes = serialization.serialize(999);
        Integer result = serialization.deserialize(bytes, Integer.class);
        assertEquals(999, result);
    }

    @Test
    void serializeLongRoundtrip() throws IOException {
        byte[] bytes = serialization.serialize(123456789L);
        Long result = serialization.deserialize(bytes, Long.class);
        assertEquals(123456789L, result);
    }

    @Test
    void serializeBooleanRoundtrip() throws IOException {
        byte[] bytes = serialization.serialize(true);
        Boolean result = serialization.deserialize(bytes, Boolean.class);
        assertTrue(result);
    }

    @Test
    void serializeEmptyStringRoundtrip() throws IOException {
        byte[] bytes = serialization.serialize("");
        String result = serialization.deserialize(bytes, String.class);
        assertEquals("", result);
    }

    @Test
    void serializationNumberShouldBeZero() {
        assertEquals(0, serialization.getSerializationNumber());
    }

    @Test
    void serializeRecordRoundtrip() throws IOException {
        TestRecord original = new TestRecord("record-test", 200, List.of("x", "y"));
        byte[] bytes = serialization.serialize(original);
        TestRecord result = serialization.deserialize(bytes, TestRecord.class);
        assertEquals(original, result);
    }

    @Test
    void serializeRecordWithNullFieldsRoundtrip() throws IOException {
        TestRecord original = new TestRecord(null, 0, null);
        byte[] bytes = serialization.serialize(original);
        TestRecord result = serialization.deserialize(bytes, TestRecord.class);
        assertEquals(original, result);
    }

    @Test
    void serializedBytesShouldDifferForDifferentObjects() throws IOException {
        byte[] bytes1 = serialization.serialize("hello");
        byte[] bytes2 = serialization.serialize("world");
        assertFalse(java.util.Arrays.equals(bytes1, bytes2));
    }
}
