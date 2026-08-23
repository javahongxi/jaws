package org.hongxi.jaws.rpc;

/**
 * future task state
 * <p>
 * Created by shenhongxi on 2020/8/23.
 *
 */
public enum FutureState {
    /**
     * the task is doing
     **/
    DOING(0),
    /**
     * the task is done
     **/
    DONE(1),
    /**
     * the task is canceled
     **/
    CANCELED(2);

    public final int value;

    FutureState(int value) {
        this.value = value;
    }

    public boolean isDoneState() {
        return this == DONE || this == CANCELED;
    }

    public boolean isDoingState() {
        return this == DOING;
    }
}
