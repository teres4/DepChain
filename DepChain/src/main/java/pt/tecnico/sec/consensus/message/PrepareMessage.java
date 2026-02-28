package pt.tecnico.sec.consensus.message;

// broadcast by a view's leader to all replicas
public class PrepareMessage extends HotStuffMessage {
    private final Block node;   // the proposed block/command
    private final QuorumCertificate highQC; // safety justification

    public PrepareMessage(int viewNumber, Block node, QuorumCertificate highQC, long senderId) {
        super(HotStuffMessageType.PREPARE, viewNumber, senderId);
        this.node = node;
        this.highQC = highQC;
    }

    public Block getNode() { return node; }
    public QuorumCertificate getHighQC() { return highQC; }

    @Override
    public String toString() {
        return "PrepareMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
               ", node=" + node + ", highQC=" + highQC + "}";
    }
}