package pt.tecnico.sec.consensus.message;

// broadcast by leader after collecting n-f PREPARE votes
public class PreCommitMessage extends HotStuffMessage {
    // in the pre commit message, the Block is null
    // this is because the block will be embedded in the prepareQC
    private final QuorumCertificate prepareQC;

    public PreCommitMessage(int viewNumber, QuorumCertificate prepareQC, long senderId) {
        super(HotStuffMessageType.PRE_COMMIT, viewNumber, senderId);
        this.prepareQC = prepareQC;
    }

    public QuorumCertificate getPrepareQC() { return prepareQC; }

    @Override
    public String toString() {
        return "PreCommitMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
               ", prepareQC=" + prepareQC + "}";
    }
}