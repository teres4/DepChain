package pt.tecnico.sec.consensus;

public class Node {

    private final String hash;

    private final String command; // TODO: define command structure step 6

    private final String parentHash;

    private QuorumCertificate justifyQC; // QC that justifies this block

    public Node(String hash, String command, String parentHash, QuorumCertificate justifyQC) {
        this.hash = hash;
        this.command = command;
        this.parentHash = parentHash;
        this.justifyQC = justifyQC;
    }

    public String getHash() {
        return hash;
    }

    public String getCommand() {
        return command;
    }

    public String getParentHash() {
        return parentHash;
    }

    public QuorumCertificate getJustifyQC() {
        return justifyQC;
    }

    @Override
    public String toString() {
        return "Node{hash='" + hash + "', command='" + command + "', parentHash='" + parentHash + "'}";
    }

}
