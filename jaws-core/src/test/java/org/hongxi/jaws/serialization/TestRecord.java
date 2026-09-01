package org.hongxi.jaws.serialization;

import java.io.Serializable;
import java.util.List;

/**
 * Java Record for serialization unit tests
 */
record TestRecord(String name, int value, List<String> tags) implements Serializable {
}
