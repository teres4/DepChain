package pt.tecnico.sec.consensus;

public class QuorumCertificate {

    private int viewNumber;
    private String nodeHash; // hash of the node that this QC certifies
    private String type; // "prepare", "pre-commit", "commit"
    private String w; // threshold signatures combined value to check

    public QuorumCertificate(int viewNumber, String nodeHash, String type, String w) {
        this.viewNumber = viewNumber;
        this.nodeHash = nodeHash;
        this.type = type;
        this.w = w;
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

    public String getW() {
        return w;
    }
}
