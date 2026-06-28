package org.hongxi.jaws.serialize;

import java.io.Serializable;
import java.util.List;

/**
 * 序列化单元测试用的 Java Record
 */
record TestRecord(String name, int value, List<String> tags) implements Serializable {
}
