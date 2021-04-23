package org.hongxi.jaws.protocol.example;

import java.io.Serializable;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class SimpleObject implements Serializable {

    private static final long serialVersionUID = 1845894491038386456L;
    private String name = "";
    private int age = 0;

    public SimpleObject(String name) {
        this.name = name;
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

    @Override
    public String toString() {
        return "name: " + name + " age: " + age;
    }

}
