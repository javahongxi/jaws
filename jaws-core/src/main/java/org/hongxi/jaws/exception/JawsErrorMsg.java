package org.hongxi.jaws.exception;

import java.io.Serializable;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public record JawsErrorMsg(int status, int errorCode, String message) implements Serializable {
    private static final long serialVersionUID = -5483348908144912517L;
}
