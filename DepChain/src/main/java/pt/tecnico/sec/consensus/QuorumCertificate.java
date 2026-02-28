package pt.tecnico.sec.consensus;

public class QuorumCertificate {
    // A Quorum Certificate (QC) over a tuple htype, viewNumber , nodei is a data
    // type that combines a collection of signatures for the same tuple signed by (n
    // − f )replicas.
    // Given a QC qc, we use qc.type, qc.viewNumber , qc.node to refer to the
    // matching fields of the originaltuple.

    private int viewNumber;
    private String nodeHash; // hash of the node that this QC certifies
    private String type; // "prepare", "pre-commit", "commit" ??
    private String signatures; // TODO: define signature structure

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
}
