package org.hongxi.jaws.sample.api.model;

import java.io.Serializable;

/**
 * Created by shenhongxi on 2021/4/26.
 */
public record Phone(Integer number) implements Serializable {
    private static final long serialVersionUID = -56932839374275492L;
}
