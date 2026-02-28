package pt.tecnico.sec.consensus.message;

// broadcast by leader after collecting n-f COMMIT votes
// commitQC is proof that n-f replicas voted COMMIT on this block
// upon receiving this, replicas will execute the commands in commitQC.node and transition to the next view
public class DecideMessage extends HotStuffMessage {
    private final QuorumCertificate commitQC;

    public DecideMessage(int viewNumber, QuorumCertificate commitQC, long senderId) {
        super(HotStuffMessageType.DECIDE, viewNumber, senderId);
        this.commitQC = commitQC;
    }

    public QuorumCertificate getCommitQC() { return commitQC; }

    @Override
    public String toString() {
        return "DecideMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
               ", commitQC=" + commitQC + "}";
    }
}