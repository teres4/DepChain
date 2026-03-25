package pt.tecnico.sec.consensus.message;

public class MissingNodeMessage extends HotStuffMessage {
    private final String nodeHash;

    public MissingNodeMessage(int viewNumber, String nodeHash, long senderId) {
        super(HotStuffMessageType.MISSING_NODE_REQUEST, viewNumber, senderId);
        this.nodeHash = nodeHash;
    }

    public String getNodeHash() {
        return nodeHash;
    }

    @Override
    public String toString() {
        return "MissingNodeMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
                ", nodeHash=" + nodeHash + "}";
    }

}
