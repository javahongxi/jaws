package org.hongxi.jaws.protocol.example;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public interface IHello {
    String hello();

    String hello(String name);

    String hello(Model model);

    String hello(int age);

    String hello(byte b);

    String hello(String name, int age, Model model);

    String hello(byte[] bs);

    String hello(int[] is);

    String hello(String[] s);

    String hello(Model[] model);

    String hello(byte[][] bs);

    String hello(int[][] is);

    String hello(String[][] s);

    String hello(Model[][] model);

    Model objResult(String name, int age);

    Model[] objArrayResult(String name, int age);

    void voidResult(String name, int age);

    Model nullResult();

    void helloException();

    UnSerializableClass helloSerializable();

    void helloSerializable(UnSerializableClass unSerializableClass);
}
