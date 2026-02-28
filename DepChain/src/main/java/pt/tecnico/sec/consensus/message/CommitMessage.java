package pt.tecnico.sec.consensus.message;

// broadcast by leader after collecting n-f PRE_COMMIT votes
// precommitQC is proof that n-f replicas voted PRE_COMMIT on this block
// because the PRE_COMMIT votes come with a signature only being possible to generate it using the private kley of the replica
public class CommitMessage extends HotStuffMessage {
    private final QuorumCertificate precommitQC;

    public CommitMessage(int viewNumber, QuorumCertificate precommitQC, long senderId) {
        super(HotStuffMessageType.COMMIT, viewNumber, senderId);
        this.precommitQC = precommitQC;
    }

    public QuorumCertificate getPrecommitQC() { return precommitQC; }

    @Override
    public String toString() {
        return "CommitMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
               ", precommitQC=" + precommitQC + "}";
    }
}