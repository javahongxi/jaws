package org.jaws.test;

import lombok.Data;

import java.io.Serializable;

/**
 * Created by shenhongxi on 2021/4/25.
 */
@Data
public class User implements Serializable {

    private static final long serialVersionUID = 6190590587012468969L;

    private String name;

    private int age;
}
