package org.hongxi.summer.common;

/**
 * Created by shenhongxi on 2020/6/27.
 */
public enum URLParamType {

    codec("codec", "summer");

    public String name;
    public String value;

    URLParamType(String name, String value) {
        this.name = name;
        this.value = value;
    }
}
