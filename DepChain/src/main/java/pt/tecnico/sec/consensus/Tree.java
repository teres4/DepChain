package pt.tecnico.sec.consensus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class Tree {

    private Node root;

    private QuorumCertificate lockedQC; // initially ⊥, storing the highest QC for which a replica voted commit
    private QuorumCertificate prepareQC; // initially ⊥, storing the highest QC for which a replica voted pre-commit.

    private Node executedNode; // highest node whose branch has been executed.

    private final Map<String, Node> treeMap = new HashMap<>();

    public Tree() {
        this.root = new Node("genesis", "genesis", "genesis", null);
        this.lockedQC = new QuorumCertificate(0, root.getHash(), "prepare", "");
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

    public void setRoot(Node root) {
        this.root = root;
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
        if (node == null || ancestor == null) return false;

        String currentHash = node.getHash();
        String ancestorHash = ancestor.getHash();

        if (currentHash.equals(ancestorHash)) return true;

        String parentHash = node.getParentHash();
        while (parentHash != null) {
            if (parentHash.equals(ancestorHash)) return true;

            Node parent = treeMap.get(parentHash);
            if (parent == null) break;

            parentHash = parent.getParentHash();
        }
        return false;
    }
    
    public void pruneConflictingBranches(Node decidedNode) {
        if (decidedNode == null) return;

        // build the committed chain hashes
        Set<String> committedChain = new HashSet<>();
        String currentHash = decidedNode.getHash();
        while (currentHash != null) {
            committedChain.add(currentHash);
            Node current = treeMap.get(currentHash);
            if (current == null) break;
            currentHash = current.getParentHash();
        }

        // remove anything not on committed chain and not extending decided node
        Iterator<Map.Entry<String, Node>> iterator = treeMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Node> entry = iterator.next();
            Node node = entry.getValue();

            if (committedChain.contains(node.getHash())) continue;
            if (extendsFrom(node, decidedNode)) continue;

            System.out.println("Pruning conflicting node: " + node.getHash() 
                + " (command: " + node.getCommand() + ")");
            iterator.remove();
        }
    }
    
}
