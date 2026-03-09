package pt.tecnico.sec.consensus;

public class QuorumCertificate {

    private int viewNumber;
    private String nodeHash; // hash of the node that this QC certifies
    private String type; // "prepare", "pre-commit", "commit"
    private String signatures;

    public QuorumCertificate(int viewNumber, String nodeHash, String type, String signatures) {
        this.viewNumber = viewNumber;
        this.nodeHash = nodeHash;
        this.type = type;
        this.signatures = signatures;
    }

    public int getViewNumber() {
        return viewNumber;
    }

    public String getNodeHash() {
        return nodeHash;
    }

    public String getType() {
        return type;
    }

    public String getSignatures() {
        return signatures;
    }
}
