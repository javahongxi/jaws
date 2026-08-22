package org.hongxi.jaws.common;

/**
 * Lifecycle states of a {@code Channel}, from creation to close:
 * UNINIT, INIT, ALIVE, UNALIVE and CLOSE, each carrying an integer value.
 * <p>
 * Callers typically use the {@code is*State()} helpers to decide whether a
 * channel is ready for traffic or has to be reconnected/rebuilt.
 * <p>
 * Created by shenhongxi on 2020/6/25.
 */
public enum ChannelState {
    UNINIT(0),

    INIT(1),

    ALIVE(2),

    UNALIVE(3),

    CLOSE(4);

    private final int value;

    ChannelState(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public boolean isUnInitState() {
        return this == UNINIT;
    }

    public boolean isInitState() {
        return this == INIT;
    }

    public boolean isAliveState() {
        return this == ALIVE;
    }

    public boolean isUnAliveState() {
        return this == UNALIVE;
    }

    public boolean isCloseState() {
        return this == CLOSE;
    }
}
