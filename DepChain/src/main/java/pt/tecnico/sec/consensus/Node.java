package pt.tecnico.sec.consensus;

import java.util.List;

public class Node {

    private final String hash;

    private final String command; // TODO: define command structure step 6

    private final Node parent;
    // children?

    private QuorumCertificate justifyQC; // QC that justifies this block

    public Node(String hash, String command, Node parent, QuorumCertificate justifyQC) {
        this.hash = hash;
        this.command = command;
        this.parent = parent;
        this.justifyQC = justifyQC;
    }

    public String getHash() {
        return hash;
    }

    public Node getParent() {
        return parent;
    }

    public QuorumCertificate getJustifyQC() {
        return justifyQC;
    }

}
