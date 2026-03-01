package pt.tecnico.sec.consensus;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberManager;

public class Consensus {
    // current viewnumber
    private AtomicInteger viewNumber;

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

    private PrivateKey myPrivateKey;
    private Map<Long, PublicKey> replicaPublicKeys;

    // New-view message collection for leader
    private final Map<Long, NewViewMessage> newViewMessages = new ConcurrentHashMap<>();

    private final Queue<PrepareVoteMessage> prepareVotes = new ConcurrentLinkedQueue<>();
    private final Queue<PreCommitVoteMessage> preCommitVotes = new ConcurrentLinkedQueue<>();
    private final Queue<CommitVoteMessage> commitVotes = new ConcurrentLinkedQueue<>();

    private volatile boolean newViewInterrupt = false;

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

        try {
            this.myPrivateKey = Crypto.getPrivateKey("config/keys/private_key" + (replicaID + 1) + ".pem");
            this.replicaPublicKeys = new HashMap<>();

            for (int i = 0; i < totalReplicas; i++) {
                PublicKey pubKey = Crypto.getPublicKey("config/keys/public_key" + (i + 1) + ".pem");
                replicaPublicKeys.put((long) i, pubKey);
            }
        } catch (Exception e) {
            System.err.println("Failed to load cryptographic keys: " + e.getMessage());
            e.printStackTrace();
        }

    }

    // one thread
    public void consensusLoop() {
        executorService.submit(() -> authPerfLink.receive());

        authPerfLink.setMessageListener((content) -> {
            try {
                HotStuffMessage msg = HotStuffMessage.fromJson(content);
                executorService.submit(() -> handleMessage(msg));
            } catch (Exception e) {
                System.err.println("Failed to handle consensus message: " + e.getMessage());
            }
        });
        if (amLeader()) {

        }
        // prepare();
        // precommit();
        // commit();
        // decide();

    }

    public void consensusLoopLeader() {

    }

    public boolean amLeader() {
        return replicaID == leaderID;
    }

    public boolean amLeader(int viewNumber) {
        return replicaID == (viewNumber % totalReplicas);
    }

    public synchronized Phase getCurrentPhase() {
        return currentPhase;
    }

    public synchronized void setCurrentPhase(Phase phase) {
        this.currentPhase = phase;
    }

    public synchronized boolean checkPhase(Phase phase) {
        if (this.currentPhase != phase) {
            System.err.println(
                    "Invalid or Outdated phase: expected " + phase + " but current phase is " + this.currentPhase);
            return false;
        }
        return true;
    }

    // If ANYONE wants to start a new view, it sends a new view message to the new
    // LEader and ONLY THE LEADER waits for (n-f) responses.
    public void startNewView() throws Exception {
        // send new view message to all replicas
        NewViewMessage newViewMessage = new NewViewMessage(this.viewNumber.get(), tree.getPrepareQC(), this.replicaID);
        broadcastMessage(newViewMessage);
    }

    // Leader collects new-view messages and moves to prepare phase
    public synchronized QuorumCertificate onReceiveNewViewMessage(NewViewMessage newViewMessage) {

        if (newViewMessage.getViewNumber() <= this.viewNumber.get()) {
            System.err.println("Received new-view message for view " + newViewMessage.getViewNumber() +
                    " but current view is " + this.viewNumber);
            return null;
        }

        if (!amLeader(newViewMessage.getViewNumber() + 1)) {
            System.err.println("Received new-view message for view " + newViewMessage.getViewNumber() +
                    " but am not Leader for next view");
            return null;
        }

        newViewMessages.put(newViewMessage.getSenderId(), newViewMessage);

        // if received (n-f) new-view messages
        if (newViewMessages.size() >= this.quorumSize) {
            // choose highest prepareQC from collected new-view messages
            QuorumCertificate highQC = getHighestQC(newViewMessages.values().stream().toList());

            // clear collected new-view messages from this view for next view
            for (Long senderId : newViewMessages.keySet()) {
                if (newViewMessages.get(senderId).getViewNumber() == this.viewNumber.get()) {
                    newViewMessages.remove(senderId);
                }
            }
            prepare(highQC); // TODO:
            return highQC;
        }
        return null;
    }

    // prepare phase - leader creates new leaf, broadcasts prepare message with new
    // leaf and highQC
    public void prepare(QuorumCertificate highQC) throws Exception {
        if (!amLeader()) {
            return;
        }

        setCurrentPhase(Phase.PREPARE);

        Node newNode = createLeaf(highQC, "COMMAND"); // TODO: define command
        tree.addNode(newNode);

        // broadcast prepare message
        PrepareMessage prepareMessage = new PrepareMessage(this.viewNumber.get(), newNode, highQC, this.replicaID);
        broadcastMessage(prepareMessage);
    }

    // prepare message from leader received
    public void onReceivePrepareMessage(PrepareMessage prepareMessage) throws Exception {
        if (prepareMessage.getViewNumber() != this.viewNumber.get()) {
            System.err.println("Received prepare message for view " + prepareMessage.getViewNumber() +
                    " but current view is " + this.viewNumber);
            return;
        }

        if (safeNode(prepareMessage.getNode(), prepareMessage.getHighQC())) {
            // if replica accepts the prepare message, it adds the new node to its tree
            tree.addNode(prepareMessage.getNode());

            String messageToSign = createVoteMessage(HotStuffMessageType.PREPARE_VOTE, this.viewNumber.get(),
                    prepareMessage.getNode().getHash());
            String partialSig = tsign(messageToSign);

            // send prepare vote with partial signature to leader
            PrepareVoteMessage voteMessage = new PrepareVoteMessage(this.viewNumber.get(), prepareMessage.getNode(),
                    this.replicaID, partialSig);

            // Send vote to leader
            authPerfLink.sendData(this.leaderID, voteMessage);

        }
    }

    // prepare vote message from replica received by leader
    public synchronized void onReceivePrepareVoteMessage(PrepareVoteMessage voteMessage) throws Exception {
        if (!amLeader() || !checkPhase(Phase.PREPARE)) {
            return;
        }

        // Validate view number - only accept votes for current view
        if (voteMessage.getViewNumber() != this.viewNumber.get()) {
            System.err.println("Received prepare vote for view " + voteMessage.getViewNumber() +
                    " but current view is " + this.viewNumber.get());
            return;
        }

        // get number of prepare votes for the current proposal
        // if leader receives (n - f) prepare votes for curProposal, then combine them
        // into precommit QC and move to pre-commit phase

        prepareVotes.add(voteMessage);

        if (prepareVotes.size() >= this.quorumSize) {
            // combine prepare votes into precommit QC
            QuorumCertificate preCommitQC = combinePrepareVotes(voteMessage.getNode(), prepareVotes);
            // move to pre-commit phase
            this.viewNumber.incrementAndGet();
            this.prepareVotes.clear();
            precommit(preCommitQC);
        }

    }

    public QuorumCertificate combinePrepareVotes(Node node, Queue<PrepareVoteMessage> votes) {
        // Combine prepare votes into a QuorumCertificate for the given node

        return null; // TODO: implement proper QC creation with threshold signatures
    }

    // pre-commit phase
    public void precommit(QuorumCertificate prepareQC) throws Exception {
        if (!amLeader()) {
            return;
        }
        setCurrentPhase(Phase.PRE_COMMIT);
        // Broadcast prepareQC in pre-commit messages
        PreCommitMessage preCommitMessage = new PreCommitMessage(this.viewNumber.get(), prepareQC, this.replicaID);
        broadcastMessage(preCommitMessage);
    }

    public void onReceivePreCommitMessage(PreCommitMessage preCommitMessage) {
        if (preCommitMessage.getViewNumber() != this.viewNumber.get()) {
            System.err.println("Received pre-commit message for view " + preCommitMessage.getViewNumber() +
                    " but current view is " + this.viewNumber);
            return;
        }

    }

    public void onReceivePreCommitVoteMessage(PreCommitVoteMessage voteMessage) {
        if (!checkPhase(Phase.PRE_COMMIT) || !amLeader()) {
            return;
        }
    }

    // commit phase
    public void commit() {
        if (!amLeader()) {
            return;
        }
        setCurrentPhase(Phase.COMMIT);
    }

    public void onReceiveCommitMessage(CommitMessage commitMessage) {

    }

    public void onReceiveCommitVoteMessage(CommitVoteMessage voteMessage) {
        if (!checkPhase(Phase.COMMIT) || !amLeader()) {
            return;
        }
    }

    // decide phase
    public void decide() {
        if (!amLeader()) {
            return;
        }
        setCurrentPhase(Phase.DECIDE);
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

    // TODO implement threshold signature
    private String tsign(String message) {
        try {
            return Crypto.signMessage(message, myPrivateKey);
        } catch (Exception e) {
            System.err.println("Failed to generate partial signature: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private String createVoteMessage(HotStuffMessageType type, int viewNumber, String nodeHash) {
        return String.format("VOTE|type=%s|view=%d|node=%s", type, viewNumber, nodeHash);
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
                executorService.submit(() -> {
                    try {
                        authPerfLink.sendData(i, message);
                    } catch (Exception e) {
                        System.err.println("Failed to send message to replica " + i + ": " + e.getMessage());
                    }
                });
                // TODO: send message to my replica self
            }
        }
    }

    public void handleMessage(HotStuffMessage msg) {
        if (msg.getViewNumber() != this.viewNumber.get()) {
            System.err.println("Ignoring message from view " + msg.getViewNumber() +
                    " (current view: " + this.viewNumber + ")");
            return;
        }

        try {
            switch (msg.getHotStuffType()) {
                case NEW_VIEW -> onReceiveNewViewMessage((NewViewMessage) msg);
                case PREPARE -> onReceivePrepareMessage((PrepareMessage) msg);
                case PREPARE_VOTE -> onReceivePrepareVoteMessage((PrepareVoteMessage) msg);
                case PRE_COMMIT -> onReceivePreCommitMessage((PreCommitMessage) msg);
                case PRE_COMMIT_VOTE -> onReceivePreCommitVoteMessage((PreCommitVoteMessage) msg);
                case COMMIT -> onReceiveCommitMessage((CommitMessage) msg);
                case COMMIT_VOTE -> onReceiveCommitVoteMessage((CommitVoteMessage) msg);
                case DECIDE -> onReceiveDecideMessage((DecideMessage) msg);
                default -> System.err.println("Unknown message type: " + msg.getHotStuffType());
            }
        } catch (Exception e) {
            System.err.println("Failed to handle message of type " + msg.getHotStuffType() + ": " + e.getMessage());
        }
    }
}
