package org.hongxi.jaws.protocol.example;

import java.util.Arrays;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class Hello implements IHello {
    private String protocol;

    public String hello() {
        return protocol + ": " + "void";
    }

    public String hello(String name) {
        return protocol + ": " + name;
    }

    public String hello(Model model) {
        return protocol + ": " + model.toString();
    }

    public String hello(int age) {
        return protocol + ": " + age;
    }

    public String hello(byte age) {
        return protocol + ": " + age;
    }

    public String hello(String name, int age, Model model) {
        return protocol + ": " + age + name + model.toString();
    }

    public String hello(byte[] bs) {
        return protocol + ": " + Arrays.toString(bs);
    }

    public String hello(int[] bs) {
        return protocol + ": " + Arrays.toString(bs);
    }

    public String hello(String[] bs) {
        return protocol + ": " + Arrays.toString(bs);
    }

    public String hello(Model[] bs) {
        return protocol + ": " + Arrays.toString(bs);
    }

    public String hello(byte[][] bs) {
        return protocol + ": " + Arrays.toString(bs[0]);
    }

    public String hello(int[][] bs) {
        return protocol + ": " + Arrays.toString(bs[0]);
    }

    public String hello(String[][] bs) {
        return protocol + ": " + Arrays.toString(bs[0]);
    }

    public String hello(Model[][] bs) {
        return protocol + ": " + Arrays.toString(bs[0]);
    }

    public Model objResult(String name, int age) {
        Model mode = new Model(name, age, Model.class);

        return mode;
    }

    public Model[] objArrayResult(String name, int age) {
        Model mode = new Model(name, age, Model.class);

        return new Model[] {mode};
    }

    public Model nullResult() {
        return null;
    }

    public void voidResult(String name, int age) {
        String result = "voidResult: " + name + "," + age;
        System.out.println("server say: " + result);
    }

    public void helloException() {
        throw new RuntimeException(protocol + " hello exception");
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    @Override
    public UnSerializableClass helloSerializable() {
        return new UnSerializableClass();
    }

    @Override
    public void helloSerializable(UnSerializableClass unSerializableClass) {
        System.out.println("I've been called");
    }
}
