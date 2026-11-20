package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * A persistent-instance write relayed from the node that received the client
 * request to the current Raft leader — only the leader may append. {@code command}
 * is the Base64 of the serialized {@code NamingCommand}; the receiving leader
 * applies it locally and never forwards again, which is what keeps the relay
 * from looping when leadership moves mid-flight.
 *
 * @author shenhongxi
 */
public class RaftWriteForwardRequest extends Request {

    private String command;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }
}