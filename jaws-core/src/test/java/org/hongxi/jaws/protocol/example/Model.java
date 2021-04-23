package org.hongxi.jaws.protocol.example;

import java.io.Serializable;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class Model implements Serializable {
    private static final long serialVersionUID = -6642850886054518156L;

    private String name;
    private int age;
    private Class<?> type;
    private long[] addTimes = null; // add attention/fan/filter times

    public Model() {}

    public Model(String name, int age, Class<?> type) {
        this.name = name;
        this.age = age;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public Class<?> getType() {
        return type;
    }

    public void setType(Class<?> type) {
        this.type = type;
    }

    public String toString() {
        return name + "," + age + "," + type.getName();
    }

    public long[] getAddTimes() {
        return addTimes;
    }

    public void setAddTimes(long[] addTimes) {
        this.addTimes = addTimes;
    }

}
