package pt.tecnico.sec.consensus;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.utils.MemberManager;

public class Consensus {
    // current viewnumber
    private int viewNumber;

    // current leader ID
    private final long leaderID = 0;

    // my ID
    private final long replicaID;

    // Quorum size
    private final int quorumSize;

    // total number of replicas
    private final int totalReplicas;

    // member management
    private final MemberManager memberManager;

    // message handling
    private final AuthPerfLink authPerfLink;

    // tree structure
    private final Tree tree;

    // New-view message collection for leader
    private final Map<Long, NewViewMessage> newViewMessages = new ConcurrentHashMap<>();

    // Vote tracking for each node by node hash
    private final Map<String, Set<Long>> prepareVotes = new ConcurrentHashMap<>();

    public enum Phase {
        NEW_VIEW, PREPARE, PRE_COMMIT, COMMIT, DECIDE
    };

    private Phase currentPhase;

    ExecutorService executorService = java.util.concurrent.Executors.newFixedThreadPool(10);

    public Consensus(long replicaID, int totalReplicas, MemberManager memberManager, AuthPerfLink authPerfLink) {
        this.replicaID = replicaID;
        this.totalReplicas = totalReplicas;
        this.quorumSize = totalReplicas - (totalReplicas - 1) / 3; // n-f where f = (n-1)/3
        this.memberManager = memberManager;
        this.authPerfLink = authPerfLink;
        this.tree = new Tree();
        this.viewNumber = 1; 
    }

    // tree pending commands

    // highest node whose branch has been executed.

    public void consensusLoop() {
        if (amLeader()) {
            // act both as leader and replica

        }
        // prepare();
        // precommit();
        // commit();
        // decide();

    }

    public boolean amLeader() {
        return replicaID == leaderID;
    }

    // If ANYONE wants to start a new view, it sends a new view message to all
    // replicas and ONLY THE LEADER waits for (n-f) responses.
    public void startNewView() throws Exception {
        // send new view message to all replicas
        NewViewMessage newViewMessage = new NewViewMessage(this.viewNumber, tree.getPrepareQC(), this.replicaID);
        broadcastMessage(newViewMessage);
    }

    // Leader collects new-view messages and moves to prepare phase
    // TODO case where collection of new view messages may be omitted by an incumbent leader ? 
    public synchronized QuorumCertificate onReceiveNewViewMessage(NewViewMessage newViewMessage) {
        if (!amLeader()) {
            return null;
        }
        
        if (newViewMessage.getViewNumber() != this.viewNumber) {
            System.err.println("Received new-view message for view " + newViewMessage.getViewNumber() + 
                             " but current view is " + this.viewNumber);
            return null;
        }
        
        newViewMessages.put(newViewMessage.getSenderId(), newViewMessage);

        // if received (n-f) new-view messages
        if (newViewMessages.size() >= this.quorumSize) {
            // choose highest prepareQC from collected new-view messages
            QuorumCertificate highQC = getHighestQC(newViewMessages.values().stream().toList());
            
            // clear collected new-view messages for next view
            newViewMessages.clear();
            return highQC;
        }
        return null;
    }

    // prepare phase - leader creates new leaf, broadcasts prepare message with new leaf and highQC
    public void prepare(QuorumCertificate highQC) throws Exception {
        if (!amLeader()) {
            return;
        }
        
        Node newNode = createLeaf(highQC, "COMMAND"); // TODO: define command
        tree.addNode(newNode);

        // broadcast prepare message
        PrepareMessage prepareMessage = new PrepareMessage(this.viewNumber, newNode, highQC, this.replicaID);
        broadcastMessage(prepareMessage);
    }

    // prepare message from leader received
    public void onReceivePrepareMessage(PrepareMessage prepareMessage) {
        if (prepareMessage.getViewNumber() != this.viewNumber) {
            System.err.println("Received prepare message for view " + prepareMessage.getViewNumber() + 
                             " but current view is " + this.viewNumber);
            return;
        }

        if (safeNode(prepareMessage.getNode(), prepareMessage.getHighQC())) {
            PrepareVoteMessage voteMessage = new PrepareVoteMessage(this.viewNumber, prepareMessage.getNode(), this.replicaID);
            
            // Send vote to leader 
            try {
                authPerfLink.sendData(leaderID, voteMessage);
            } catch (Exception e) {
                System.err.println("Failed to send prepare vote: " + e.getMessage());
            }
        }
    }

    // prepare vote message from replica received by leader
    public synchronized void onReceivePrepareVoteMessage(PrepareVoteMessage voteMessage) throws Exception {
        if (!amLeader()) {
            return;
        }
        
        // Validate view number - only accept votes for current view
        if (voteMessage.getViewNumber() != this.viewNumber) {
            System.err.println("Received prepare vote for view " + voteMessage.getViewNumber() + 
                             " but current view is " + this.viewNumber);
            return;
        }
    }

    // pre-commit phase
    public void precommit(QuorumCertificate prepareQC) throws Exception {
        if (!amLeader()) {
            return;
        }
        
        // Broadcast prepareQC in pre-commit messages
        PreCommitMessage preCommitMessage = new PreCommitMessage(this.viewNumber, prepareQC, this.replicaID);
        broadcastMessage(preCommitMessage);
    }

    public void onReceivePreCommitMessage(PreCommitMessage preCommitMessage) {

    }

    // commit phase
    public void commit() {

    }

    public void onReceiveCommitMessage(CommitMessage commitMessage) {

    }

    // decide phase
    public void decide() {

    }

    public void onReceiveDecideMessage(DecideMessage decideMessage) {

    }

    private boolean safeNode(Node node, QuorumCertificate justify) {
        QuorumCertificate lockedQC = tree.getLockedQC();

        // liveness rule = accept if justify has higher view than lockedQC
        if (justify != null && justify.getViewNumber() > lockedQC.getViewNumber()) {
            return true;
        }
        
        // safety rule: if node extends from lockedQC.node
        Node lockedNode = tree.getNode(lockedQC.getNodeHash());
        return extendsFrom(node, lockedNode);
    }

    // TODO : implement proper tree traversal to check if node extends from ancestor
    private boolean extendsFrom(Node node, Node ancestor) {
        if (node == null || ancestor == null) {
            return false;
        }
        
        return true;
    }

    private QuorumCertificate getHighestQC(List<NewViewMessage> messages) {
        QuorumCertificate highQC = null;
        int highestView = -1;
        
        for (NewViewMessage msg : messages) {
            QuorumCertificate qc = msg.getPrepareQC();
            if (qc != null && qc.getViewNumber() > highestView) {
                highestView = qc.getViewNumber();
                highQC = qc;
            }
        }
        return highQC;
    }

    private Node createLeaf(QuorumCertificate parentQC, String command) {
        // TODO HOW DO WE FIND THE PARENT NODE FOR THIS NEW NODE ?
        Node parent = null;
        
        if (parent == null) {
            throw new IllegalStateException("Parent node not found in tree for QC");
        }
        
        // TODO: is this correct? should we calculate hash here instead
        String parentHash = parent.getHash();
        
        return new Node(parentHash, command, parent, parentQC);
    }

    public void newView() {

    }

    public void broadcastMessage(HotStuffMessage message) throws Exception {
        for (int i = 0; i < totalReplicas; i++) {
            if (i != replicaID) {
                authPerfLink.sendData(i, message);
            }
        }
    }

}
