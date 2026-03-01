package pt.tecnico.sec.consensus;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberManager;

import com.weavechain.sig.ThresholdSigEd25519;
import com.weavechain.sig.ThresholdSigEd25519Params;

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

    ThresholdSigEd25519 thresholdSig;

    // Threshold signature keys
    private byte[] privateKeyShare;
    private byte[] publicKey;

    public Consensus(long replicaID, int totalReplicas, MemberManager memberManager, AuthPerfLink authPerfLink) {
        this.replicaID = replicaID;
        this.totalReplicas = totalReplicas;
        this.quorumSize = totalReplicas - (totalReplicas - 1) / 3; // n-f where f = (n-1)/3
        this.memberManager = memberManager;
        this.authPerfLink = authPerfLink;
        this.thresholdSig = new ThresholdSigEd25519(totalReplicas, quorumSize);
        this.tree = new Tree();

        // Load threshold signature keys
        try {
            this.privateKeyShare = Crypto.loadThresholdPrivateKeyShare(
                    "config/threshold-keys/private_share_" + (replicaID + 1) + ".bin");
            this.publicKey = Crypto.loadThresholdPublicKey("config/threshold-keys/public_key.bin");
        } catch (Exception e) {
            System.err.println("Failed to load threshold keys: " + e.getMessage());
            throw new RuntimeException("Cannot initialize consensus without threshold keys", e);
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
    public synchronized QuorumCertificate onReceiveNewViewMessage(NewViewMessage newViewMessage) throws Exception {

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

        // if highQC is null, then this is the very first proposal and the new node
        // should extend from the root
        Node parentNode = (highQC != null) ? tree.getNode(highQC.getNodeHash()) : tree.getRoot();

        Node newNode = createLeaf(parentNode, "COMMAND"); // TODO: define command
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

        String prepareNodeHash = prepareMessage.getHighQC().getNodeHash();
        Node prepareNode = tree.getNode(prepareNodeHash);

        if (tree.extendsFrom(prepareMessage.getNode(), prepareNode)
                && safeNode(prepareMessage.getNode(), prepareMessage.getHighQC())) {
            // if replica accepts the prepare message, it adds the new node to its tree
            tree.addNode(prepareMessage.getNode());

            String partialSig = tsign(HotStuffMessageType.PREPARE_VOTE, this.viewNumber.get(), prepareNodeHash);

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
        if (prepareVotes.size() >= this.quorumSize) {
            System.err.println("Already received enough prepare votes for view " + this.viewNumber.get());
            return;
        }
        prepareVotes.add(voteMessage);

        if (prepareVotes.size() >= this.quorumSize) {
            // combine prepare votes into precommit QC
            @SuppressWarnings("unchecked")
            Queue<HotStuffMessage> voteQueue = (Queue<HotStuffMessage>) (Queue<?>) prepareVotes;
            QuorumCertificate preCommitQC = QC(voteMessage.getNode(), voteQueue);
            // move to pre-commit phase
            this.viewNumber.incrementAndGet();
            this.prepareVotes.clear();
            precommit(preCommitQC);
        }

    }

    public QuorumCertificate QC(Node node, Queue<HotStuffMessage> votes) {
        try {
            // Use tcombine to get the combined signature
            String combinedSigBase64 = tcombine(votes);
            if (combinedSigBase64 == null) {
                return null;
            }

            // Create and return QuorumCertificate
            // Get view number and type from first vote
            HotStuffMessage firstVote = votes.peek();
            int viewNum = firstVote != null ? firstVote.getViewNumber() : this.viewNumber.get();
            String qcType = firstVote instanceof PrepareVoteMessage ? "prepare"
                    : firstVote instanceof PreCommitVoteMessage ? "pre-commit"
                            : firstVote instanceof CommitVoteMessage ? "commit" : "unknown";

            return new QuorumCertificate(
                    viewNum,
                    node.getHash(),
                    qcType,
                    combinedSigBase64);
        } catch (Exception e) {
            System.err.println("Failed to create QC: " + e.getMessage());
            return null;
        }
    }

    public String tcombine(Queue<HotStuffMessage> votes) {
        try {
            // Extract partial signatures and indices from votes
            int voteCount = votes.size();
            byte[][] signatureShares = new byte[voteCount][];
            int[] indices = new int[voteCount];

            int i = 0;
            for (HotStuffMessage vote : votes) {
                String partialSig = null;
                long senderId = vote.getSenderId();

                // Extract partial signature based on message type
                if (vote instanceof PrepareVoteMessage) {
                    partialSig = ((PrepareVoteMessage) vote).getPartialSig();
                } else if (vote instanceof PreCommitVoteMessage) {
                    partialSig = ((PreCommitVoteMessage) vote).getPartialSig();
                } else if (vote instanceof CommitVoteMessage) {
                    partialSig = ((CommitVoteMessage) vote).getPartialSig();
                }

                if (partialSig != null) {
                    signatureShares[i] = java.util.Base64.getDecoder().decode(partialSig);
                    indices[i] = (int) (senderId + 1); // Convert to 1-based indexing
                    i++;
                }
            }

            // Combine signature shares
            byte[] combinedSig = Crypto.combineThresholdSignatures(thresholdSig, indices, signatureShares);
            return java.util.Base64.getEncoder().encodeToString(combinedSig);
        } catch (Exception e) {
            System.err.println("Failed to combine signatures: " + e.getMessage());
            return null;
        }
    }

    public String tsign(HotStuffMessageType type, int viewNumber, String nodeHash) {
        try {
            // Create message to sign
            String message = createVoteMessage(type, viewNumber, nodeHash);
            byte[] messageBytes = message.getBytes();

            // Sign with this replica's private key share (1-based indexing)
            byte[] signatureShare = Crypto.thresholdSign(
                    thresholdSig,
                    (int) (replicaID + 1),
                    privateKeyShare,
                    messageBytes);

            // Return base64-encoded signature
            return java.util.Base64.getEncoder().encodeToString(signatureShare);
        } catch (Exception e) {
            System.err.println("Failed to create threshold signature: " + e.getMessage());
            return null;
        }
    }

    /**
     * Verify a threshold signature (typically a combined signature from a QC)
     * 
     * @param signatureBase64 Base64-encoded threshold signature (can be partial or
     *                        combined)
     * @param message         The message that was signed
     * @param senderId        The sender ID (used for context, not for verification)
     * @return true if signature is valid, false otherwise
     */
    public boolean tverify(String signatureBase64, String message, long senderId) {
        try {
            if (signatureBase64 == null || signatureBase64.isEmpty()) {
                return false;
            }

            // Decode the signature
            byte[] signature = java.util.Base64.getDecoder().decode(signatureBase64);
            byte[] messageBytes = message.getBytes();

            // Verify using the threshold public key
            return Crypto.verifyThresholdSignature(publicKey, messageBytes, signature);
        } catch (Exception e) {
            System.err.println("Failed to verify threshold signature: " + e.getMessage());
            return false;
        }
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

        return tree.extendsFrom(node, lockedNode);
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

    private Node createLeaf(Node parent, String command) throws Exception {
        if (parent == null) {
            throw new IllegalStateException("Parent node cannot be null");
        }

        String nodeData = parent.getHash() + "|" + command + "|" + viewNumber.get();
        String nodeHash;
        nodeHash = Crypto.hash(nodeData);

        return new Node(nodeHash, command, parent, null);
    }

    public void broadcastMessage(HotStuffMessage message) throws Exception {
        for (int i = 0; i < totalReplicas; i++) {
            if (i != replicaID) {
                final int replicaIndex = i;
                executorService.submit(() -> {
                    try {
                        authPerfLink.sendData(replicaIndex, message);
                    } catch (Exception e) {
                        System.err.println("Failed to send message to replica " + replicaIndex + ": " + e.getMessage());
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
