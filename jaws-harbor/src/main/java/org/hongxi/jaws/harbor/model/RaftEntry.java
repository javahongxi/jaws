package org.hongxi.jaws.harbor.model;

/**
 * One Raft log entry in wire form: {@code data} is the Base64 of the serialized
 * state-machine command. The internal {@code raft.LogEntry} carries raw bytes;
 * this DTO is the JSON shape that rides {@code RaftAppendRequest}.
 *
 * @author shenhongxi
 */
public class RaftEntry {

    private long index;
    private long term;
    private String data;

    public RaftEntry() {
    }

    public RaftEntry(long index, long term, String data) {
        this.index = index;
        this.term = term;
        this.data = data;
    }

    public long getIndex() {
        return index;
    }

    public void setIndex(long index) {
        this.index = index;
    }

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}