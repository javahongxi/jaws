package org.hongxi.jaws.serialize;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 序列化单元测试用的简单 POJO
 */
class TestPojo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private int value;
    private List<String> tags;

    TestPojo() {
    }

    TestPojo(String name, int value, List<String> tags) {
        this.name = name;
        this.value = value;
        this.tags = tags;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestPojo that = (TestPojo) o;
        return value == that.value
                && Objects.equals(name, that.name)
                && Objects.equals(tags, that.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value, tags);
    }

    @Override
    public String toString() {
        return "TestPojo{name='" + name + "', value=" + value + ", tags=" + tags + "}";
    }
}
