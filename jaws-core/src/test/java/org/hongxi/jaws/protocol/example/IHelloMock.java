package org.hongxi.jaws.protocol.example;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class IHelloMock implements IHello {

    @Override
    public String hello() {
        // TODO Auto-generated method stub
        return "I'm a mock service";
    }

    @Override
    public String hello(String name) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(Model model) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(int age) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(byte b) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(String name, int age, Model model) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(byte[] bs) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(int[] is) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(String[] s) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(Model[] model) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(byte[][] bs) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(int[][] is) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(String[][] s) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String hello(Model[][] model) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Model objResult(String name, int age) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Model[] objArrayResult(String name, int age) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void voidResult(String name, int age) {
        // TODO Auto-generated method stub

    }

    @Override
    public Model nullResult() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void helloException() {
        // TODO Auto-generated method stub

    }

    @Override
    public UnSerializableClass helloSerializable() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void helloSerializable(UnSerializableClass unSerializableClass) {
        // TODO Auto-generated method stub

    }

}
