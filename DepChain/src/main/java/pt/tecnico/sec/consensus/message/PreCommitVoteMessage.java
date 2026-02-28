package pt.tecnico.sec.consensus.message;

import pt.tecnico.sec.consensus.Node;

// sent by replica to leader after receiving PRE_COMMIT
public class PreCommitVoteMessage extends HotStuffMessage {
    private final Node node;
    private final String partialSig;

    public PreCommitVoteMessage(int viewNumber, Node node, long senderId) {
        super(HotStuffMessageType.PRE_COMMIT_VOTE, viewNumber, senderId);
        this.node = node;
        this.partialSig = null;
    }

    public Node getNode() {
        return node;
    }

    public String getPartialSig() {
        return partialSig;
    }

    @Override
    public String toString() {
        return "PreCommitVoteMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
                ", node=" + node + "}";
    }
}