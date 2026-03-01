package pt.tecnico.sec.consensus;

import java.util.HashMap;
import java.util.Map;

public class Tree {

    private Node root;

    private QuorumCertificate lockedQC; // initially ⊥, storing the highest QC for which a replica voted commit
    private QuorumCertificate prepareQC; // initially ⊥, storing the highest QC for which a replica voted pre-commit.

    private Node executedNode; // highest node whose branch has been executed.

    private final Map<String, Node> treeMap = new HashMap<>();

    public Tree() {
        this.root = new Node("genesis", "genesis", null, null);
        this.lockedQC = null;
        this.prepareQC = null;
        this.executedNode = root;
    }

    public Node getRoot() {
        return root;
    }

    public QuorumCertificate getLockedQC() {
        return lockedQC;
    }

    public QuorumCertificate getPrepareQC() {
        return prepareQC;
    }

    public Node getExecutedNode() {
        return executedNode;
    }

    public void setLockedQC(QuorumCertificate lockedQC) {
        this.lockedQC = lockedQC;
    }

    public void setPrepareQC(QuorumCertificate prepareQC) {
        this.prepareQC = prepareQC;
    }

    public void addNode(Node node) {
        treeMap.put(node.getHash(), node);
    }

    public Node getNode(String hash) {
        return treeMap.get(hash);
    }

    // check if node extends from ancestor
    public boolean extendsFrom(Node node, Node ancestor) {
        if (node == null || ancestor == null) {
            return false;
        }
        
        Node current = node;
        while (current != null) {
            if (current.equals(ancestor) || 
                (current.getHash() != null && current.getHash().equals(ancestor.getHash()))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
    
}
