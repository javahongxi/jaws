package org.hongxi.jaws.store;

import java.io.Serializable;

/**
 * Answer to an offset query or commit — the counterpart of {@link AdminViewResult}
 * on the progress-tracking side.
 *
 * <p>Absence is a distinct answer: a stored offset of 0 is a real position
 * (nothing consumed yet), while {@code found=false} means this broker has never
 * seen the group@topic@queueId at all. A bare {@code long} with a negative
 * sentinel would force every caller to remember that convention, so the flag is
 * carried explicitly and absence travels as a normal response, not an exception.
 *
 * <p>On a commit, {@code offset} is the authoritative value after the update:
 * a commit that lost to a higher stored offset (increaseOnly) reads it back
 * here instead of silently believing its own.
 */
public class OffsetResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean found;
    private long offset;

    public OffsetResult() {
    }

    public OffsetResult(boolean found, long offset) {
        this.found = found;
        this.offset = offset;
    }

    public static OffsetResult found(long offset) {
        return new OffsetResult(true, offset);
    }

    public static OffsetResult notFound() {
        return new OffsetResult(false, -1L);
    }

    public boolean isFound() {
        return found;
    }

    public void setFound(boolean found) {
        this.found = found;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    @Override
    public String toString() {
        return "OffsetResult{found=" + found + ", offset=" + offset + '}';
    }
}
