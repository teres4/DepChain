package pt.tecnico.sec.consensus.message;

import pt.tecnico.sec.consensus.Node;

// sent by replica to leader after receiving COMMIT
// replica sets lockedQC = precommitQC before sending this vote
// the lock means the replica promises not to vote for a Node that conflicts with the lockedQC's Node
// (unless it sees a QC from a higher view)
public class CommitVoteMessage extends HotStuffMessage {
    private final Node node;
    private final String partialSig;

    public CommitVoteMessage(int viewNumber, Node node, long senderId, String partialSig) {
        super(HotStuffMessageType.COMMIT_VOTE, viewNumber, senderId);
        this.node = node;
        this.partialSig = partialSig;
    }

    public Node getNode() {
        return node;
    }

    public String getPartialSig() {
        return partialSig;
    }

    @Override
    public String toString() {
        return "CommitVoteMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
                ", node=" + node + "}";
    }
}