package pt.tecnico.sec.consensus.message;

// sent by replica to leader after receiving PRE_COMMIT
public class PreCommitVoteMessage extends HotStuffMessage {
    private final Block node;
    private final String partialSig;

    public PreCommitVoteMessage(int viewNumber, Block node, long senderId) {
        super(HotStuffMessageType.PRE_COMMIT_VOTE, viewNumber, senderId);
        this.node = node;
        this.partialSig = null;
    }

    public Block getNode() { return node; }
    public String getPartialSig() { return partialSig; }

    @Override
    public String toString() {
        return "PreCommitVoteMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
               ", node=" + node + "}";
    }
}