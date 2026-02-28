package pt.tecnico.sec.consensus.message;

// sent by replica to leader after receiving COMMIT
// replica sets lockedQC = precommitQC before sending this vote
// the lock means the replica promises not to vote for a block that conflicts with the lockedQC's block
// (unless it sees a QC from a higher view)
public class CommitVoteMessage extends HotStuffMessage {
    private final Block node;
    private final String partialSig;

    public CommitVoteMessage(int viewNumber, Block node, long senderId) {
        super(HotStuffMessageType.COMMIT_VOTE, viewNumber, senderId);
        this.node = node;
        this.partialSig = null;
    }

    public Block getNode() { return node; }
    public String getPartialSig() { return partialSig; }

    @Override
    public String toString() {
        return "CommitVoteMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
               ", node=" + node + "}";
    }
}