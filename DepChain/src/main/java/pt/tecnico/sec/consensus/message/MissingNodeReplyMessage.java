package pt.tecnico.sec.consensus.message;

import java.util.List;

import pt.tecnico.sec.consensus.Node;

public class MissingNodeReplyMessage extends HotStuffMessage {
    private final List<Node> missingNodes;

    public MissingNodeReplyMessage(long senderId, int viewNumber, List<Node> missingNodes) {
        super(HotStuffMessageType.MISSING_NODE_REPLY, viewNumber, senderId);
        this.missingNodes = missingNodes;
    }

    public List<Node> getNodes() {
        return missingNodes;
    }

    @Override
    public String toString() {
        return "MissingNodeReplyMessage{" +
                "senderId=" + getSenderId() +
                ", viewNumber=" + getViewNumber() +
                ", missingNodes=" + missingNodes +
                '}';
    }

}
