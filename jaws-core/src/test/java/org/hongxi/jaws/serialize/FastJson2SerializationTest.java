package org.hongxi.jaws.serialize;

import org.hongxi.jaws.codec.Serialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FastJson2Serialization 单元测试
 */
class FastJson2SerializationTest {

    private Serialization serialization;

    @BeforeEach
    void setUp() {
        serialization = new FastJson2Serialization();
    }

    @Test
    void serializeNumberShouldReturnNonNullBytes() throws IOException {
        byte[] bytes = serialization.serialize(42);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }

    @Test
    void deserializeNumberShouldReturnOriginalValue() throws IOException {
        byte[] bytes = serialization.serialize(42);
        Integer result = serialization.deserialize(bytes, Integer.class);
        assertEquals(42, result);
    }

    @Test
    void serializeStringRoundtrip() throws IOException {
        String original = "hello jaws";
        byte[] bytes = serialization.serialize(original);
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
    void serializeNullShouldReturnBytes() throws IOException {
        byte[] bytes = serialization.serialize(null);
        assertNotNull(bytes);
    }

    @Test
    void serializeEmptyStringRoundtrip() throws IOException {
        byte[] bytes = serialization.serialize("");
        String result = serialization.deserialize(bytes, String.class);
        assertEquals("", result);
    }

    @Test
    void serializationNumberShouldBeOne() {
        assertEquals(1, serialization.getSerializationNumber());
    }

    @Test
    void serializeRecordRoundtrip() throws IOException {
        /* fastjson2 JSONB 与 record + List 字段存在兼容性问题，此处使用不含集合字段的 record */
        record SimpleRecord(String name, int value) implements Serializable {}

        SimpleRecord original = new SimpleRecord("record-test", 200);
        byte[] bytes = serialization.serialize(original);
        SimpleRecord result = serialization.deserialize(bytes, SimpleRecord.class);
        assertEquals(original, result);
    }

    @Test
    void serializeRecordWithNullFieldRoundtrip() throws IOException {
        record SimpleRecord(String name, int value) implements Serializable {}

        SimpleRecord original = new SimpleRecord(null, 0);
        byte[] bytes = serialization.serialize(original);
        SimpleRecord result = serialization.deserialize(bytes, SimpleRecord.class);
        assertEquals(original, result);
    }

    @Test
    void serializeNestedRecordRoundtrip() throws IOException {
        /* record 嵌套 record */
        record Inner(String msg) implements Serializable {}
        record Outer(String id, Inner inner) implements Serializable {}

        Outer original = new Outer("outer-1", new Inner("hello"));
        byte[] bytes = serialization.serialize(original);
        Outer result = serialization.deserialize(bytes, Outer.class);
        assertEquals(original, result);
    }

    @Test
    void securityFilterShouldBeAccessible() {
        FastJson2Serialization fjs = (FastJson2Serialization) serialization;
        assertNotNull(fjs.getSecurityFilter());
    }
}
