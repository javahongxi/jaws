package org.hongxi.jaws.store;

import java.io.Serializable;

/**
 * Answer to a lease claim or renewal: whether the caller holds the queue now,
 * and, when it does not, which consumer does. {@code holder} is null for a
 * queue nobody has claimed, so a rejection always says whether the competitor
 * is a live consumer or a claim the caller lost in between.
 */
public class LeaseResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean granted;
    private String holder;

    public LeaseResult() {
    }

    private LeaseResult(boolean granted, String holder) {
        this.granted = granted;
        this.holder = holder;
    }

    public static LeaseResult granted() {
        return new LeaseResult(true, null);
    }

    public static LeaseResult denied(String holder) {
        return new LeaseResult(false, holder);
    }

    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }

    public String getHolder() {
        return holder;
    }

    public void setHolder(String holder) {
        this.holder = holder;
    }

    @Override
    public String toString() {
        return granted ? "granted" : "denied, holder=" + holder;
    }
}
