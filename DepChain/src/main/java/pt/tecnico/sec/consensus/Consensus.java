package pt.tecnico.sec.consensus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberManager;
import pt.tecnico.sec.utils.threshsig.GroupKey;
import pt.tecnico.sec.utils.threshsig.KeyShare;
import pt.tecnico.sec.utils.threshsig.SigShare;

public class Consensus {
    // current viewnumber
    private AtomicInteger viewNumber;

    private AtomicInteger newViewNumber = new AtomicInteger(1); // view number for new view messages,
    // separate from current view number to allow replicas to send new view messages
    // for future views

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

    // will be used to check if the proposal is the same for all votes in the same
    // phase
    // volatile keyword means that changes to this variable are immediately visible
    // to all threads
    // since we can have differrent threads handling different messages, we need to
    // make sure
    // that they see the most up to date proposal when they receive votes
    private volatile Node curProposal = null;

    ExecutorService executorService = java.util.concurrent.Executors.newFixedThreadPool(10);

    private volatile Instant lastSeen = Instant.now();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final Duration TIMEOUT = Duration.ofSeconds(30); // TODO: choose appropriate timeout duration

    // Threshold signature keys
    private KeyShare privateKeyShare;
    private GroupKey publicKey;

    public Consensus(long replicaID, int totalReplicas, MemberManager memberManager, AuthPerfLink authPerfLink) {
        this.replicaID = replicaID;
        this.totalReplicas = totalReplicas;
        this.quorumSize = totalReplicas - (totalReplicas - 1) / 3; // n-f where f = (n-1)/3
        this.memberManager = memberManager;
        this.authPerfLink = authPerfLink;
        this.tree = new Tree();
        this.viewNumber = new AtomicInteger(1);

        // Load threshold signature keys
        try {
            this.privateKeyShare = Crypto.loadThresholdPrivateKeyShare(
                    "config/keys/threshold_privateShare_" + replicaID + ".bin");
            this.publicKey = Crypto.loadThresholdPublicKey("config/keys/threshold_groupkey.bin");
        } catch (Exception e) {
            System.err.println("Failed to load threshold keys: " + e.getMessage());
            throw new RuntimeException("Cannot initialize consensus without threshold keys", e);
        }

        scheduler.scheduleAtFixedRate(
                this::checkLeaderActivity,
                5,
                5,
                TimeUnit.SECONDS);
    }

    // ==================== INITIALIZATION ====================

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

    }

    // ==================== COMMON UTILITY METHODS ====================

    public boolean amLeader(int viewNumber) {
        return this.replicaID == (viewNumber % this.totalReplicas);
    }

    public int getLeader(int viewNumber) {
        return (viewNumber % this.totalReplicas);
    }

    public synchronized Phase getCurrentPhase() {
        return this.currentPhase;
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
        NewViewMessage newViewMessage = new NewViewMessage(this.viewNumber.get(), tree.getPrepareQC(), this.replicaID);
        authPerfLink.sendData(getLeader(this.newViewNumber.incrementAndGet()), newViewMessage);
    }

    public void handleMessage(HotStuffMessage msg) {
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

    public void broadcastMessage(HotStuffMessage message) throws Exception {
        for (int i = 0; i < this.totalReplicas; i++) {
            if (i != this.replicaID) {
                final int replicaIndex = i;
                executorService.submit(() -> {
                    try {
                        authPerfLink.sendData(replicaIndex, message);
                    } catch (Exception e) {
                        System.err.println("Failed to send message to replica " + replicaIndex + ": " + e.getMessage());
                    }
                });
            }
        }
        sendToSelf(message);
    }

    /**
     * Since the leader also participates in voting, we need to handle the case
     * where the leader needs to process its own messages.
     * For simplicity, we can directly call the message handler for the leader's own
     * messages instead of sending them through the network.
     * Method automatically checks if current replica is the leader and if so, it
     * processes the message directly.
     * 
     * @throws Exception
     */
    public void sendToSelf(HotStuffMessage message) throws Exception {
        if (amLeader(message.getViewNumber())) {
            executorService.submit(() -> {
                try {
                    handleMessage(message);
                } catch (Exception e) {
                    System.err.println("Failed to send message to self: " + e.getMessage());
                }
            });
        }
    }

    // ==================== CRYPTOGRAPHIC OPERATIONS ====================

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
            SigShare[] sigShares = new SigShare[votes.size()];
            int i = 0;
            for (HotStuffMessage vote : votes) {
                String partialSigBase64 = extractPartialSig(vote);
                if (partialSigBase64 == null) continue;

                byte[] sigBytes = Base64.getDecoder().decode(partialSigBase64);
                ObjectInputStream ois = new ObjectInputStream(
                        new ByteArrayInputStream(sigBytes));
                sigShares[i++] = (SigShare) ois.readObject();
            }

            // store full serialized SigShares as comma-separated base64
            // tverify() will reconstruct them and verify later
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < sigShares.length; j++) {
                if (j > 0) sb.append(",");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                new ObjectOutputStream(bos).writeObject(sigShares[j]);
                sb.append(Base64.getEncoder().encodeToString(bos.toByteArray()));
            }
            return sb.toString();

        } catch (Exception e) {
            System.err.println("Failed to combine signatures: " + e.getMessage());
            return null;
        }
    }

    public String tsign(HotStuffMessageType type, int viewNumber, String nodeHash) {
        heartbeat();
        try {
            String message = createVoteMessage(type, viewNumber, nodeHash);
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            SigShare sigShare = Crypto.thresholdSign(this.privateKeyShare, messageBytes);

            // serialize SigShare to bytes then base64 for transport
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(sigShare);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            System.err.println("Failed to create threshold signature: " + e.getMessage());
            return null;
        }
    }

    public boolean tverify(String signaturesField, String message, long senderId) {
        try {
            if (signaturesField == null || signaturesField.isEmpty()) return false;

            String[] parts = signaturesField.split(",");
            SigShare[] sigShares = new SigShare[parts.length];
            for (int i = 0; i < parts.length; i++) {
                byte[] sigBytes = Base64.getDecoder().decode(parts[i]);
                ObjectInputStream ois = new ObjectInputStream(
                        new ByteArrayInputStream(sigBytes));
                sigShares[i] = (SigShare) ois.readObject();
            }

            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            return Crypto.combineAndVerify(
                    sigShares,
                    this.quorumSize,
                    this.totalReplicas,
                    this.publicKey,
                    messageBytes);
        } catch (Exception e) {
            System.err.println("Failed to verify threshold signature: " + e.getMessage());
            return false;
        }
    }

    // ==================== HELPER METHODS ====================

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

    private String extractPartialSig(HotStuffMessage vote) {
        if (vote instanceof PrepareVoteMessage v)    return v.getPartialSig();
        if (vote instanceof PreCommitVoteMessage v)  return v.getPartialSig();
        if (vote instanceof CommitVoteMessage v)     return v.getPartialSig();
        return null;
    }

    // ==================== LEADER METHODS ====================

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
            this.viewNumber.incrementAndGet();
            prepare(highQC);
            return highQC;
        }

        return null;
    }

    // prepare phase - leader creates new leaf, broadcasts prepare message with new
    // leaf and highQC
    public void prepare(QuorumCertificate highQC) throws Exception {
        if (!amLeader(highQC.getViewNumber())) {
            return;
        }

        setCurrentPhase(Phase.PREPARE);

        // if highQC is null, then this is the very first proposal and the new node
        // should extend from the root
        Node parentNode = (highQC != null) ? tree.getNode(highQC.getNodeHash()) : tree.getRoot();

        Node newNode = createLeaf(parentNode, "COMMAND"); // TODO: define command
        this.curProposal = newNode;
        tree.addNode(newNode);

        // broadcast prepare message
        PrepareMessage prepareMessage = new PrepareMessage(this.viewNumber.get(), newNode, highQC, this.replicaID);
        this.prepareVotes.clear();
        broadcastMessage(prepareMessage);
    }

    // prepare vote message from replica received by leader
    public synchronized void onReceivePrepareVoteMessage(PrepareVoteMessage voteMessage) throws Exception {
        if (!amLeader(voteMessage.getViewNumber()) || !checkPhase(Phase.PREPARE)) {
            return;
        }

        // Validate view number - only accept votes for current view
        if (voteMessage.getViewNumber() != this.viewNumber.get()) {
            System.err.println("Received prepare vote for view " + voteMessage.getViewNumber() +
                    " but current view is " + this.viewNumber.get());
            return;
        }

        if (curProposal == null || !voteMessage.getNode().getHash().equals(curProposal.getHash())) {
            System.err.println("Received prepare vote for wrong node, ignoring");
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
            precommit(preCommitQC);
        }

    }

    // pre-commit phase
    public void precommit(QuorumCertificate prepareQC) throws Exception {
        if (!amLeader(prepareQC.getViewNumber())) {
            return;
        }
        setCurrentPhase(Phase.PRE_COMMIT);
        // Broadcast prepareQC in pre-commit messages
        PreCommitMessage preCommitMessage = new PreCommitMessage(this.viewNumber.get(), prepareQC, this.replicaID);
        this.preCommitVotes.clear();
        broadcastMessage(preCommitMessage);
    }

    public synchronized void onReceivePreCommitVoteMessage(PreCommitVoteMessage voteMessage) throws Exception {
        if (!amLeader(voteMessage.getViewNumber()) || !checkPhase(Phase.PRE_COMMIT)) {
            return;
        }

        if (voteMessage.getViewNumber() != this.viewNumber.get()) {
            System.err.println("Received pre-commit vote for view " + voteMessage.getViewNumber() +
                    " but current view is " + this.viewNumber.get());
            return;
        }

        if (curProposal == null || !voteMessage.getNode().getHash().equals(curProposal.getHash())) {
            System.err.println("Received pre-commit vote for wrong node, ignoring");
            return;
        }

        if (preCommitVotes.size() >= this.quorumSize) {
            System.err.println("Already received enough pre-commit votes for view " + this.viewNumber.get());
            return;
        }

        preCommitVotes.add(voteMessage);

        if (preCommitVotes.size() >= this.quorumSize) {
            @SuppressWarnings("unchecked")
            Queue<HotStuffMessage> voteQueue = (Queue<HotStuffMessage>) (Queue<?>) preCommitVotes;
            QuorumCertificate preCommitQC = QC(voteMessage.getNode(), voteQueue);
            commit(preCommitQC);
        }
    }

    // commit phase
    public void commit(QuorumCertificate preCommitQC) throws Exception {
        if (!amLeader(preCommitQC.getViewNumber())) {
            return;
        }
        setCurrentPhase(Phase.COMMIT);
        CommitMessage commitMessage = new CommitMessage(this.viewNumber.get(), preCommitQC, this.replicaID);
        this.commitVotes.clear();
        broadcastMessage(commitMessage);
    }

    public synchronized void onReceiveCommitVoteMessage(CommitVoteMessage voteMessage) throws Exception {
        if (!amLeader(voteMessage.getViewNumber()) || !checkPhase(Phase.COMMIT)) {
            return;
        }

        if (voteMessage.getViewNumber() != this.viewNumber.get()) {
            System.err.println("Received commit vote for view " + voteMessage.getViewNumber() +
                    " but current view is " + this.viewNumber.get());
            return;
        }

        if (curProposal == null || !voteMessage.getNode().getHash().equals(curProposal.getHash())) {
            System.err.println("Received commit vote for wrong node, ignoring");
            return;
        }

        if (commitVotes.size() >= this.quorumSize) {
            System.err.println("Already received enough commit votes for view " + this.viewNumber.get());
            return;
        }

        commitVotes.add(voteMessage);

        if (commitVotes.size() >= this.quorumSize) {
            @SuppressWarnings("unchecked")
            Queue<HotStuffMessage> voteQueue = (Queue<HotStuffMessage>) (Queue<?>) commitVotes;
            QuorumCertificate commitQC = QC(voteMessage.getNode(), voteQueue);
            decide(commitQC);
        }
    }

    // decide phase
    public void decide(QuorumCertificate commitQC) throws Exception {
        if (!amLeader(commitQC.getViewNumber())) {
            return;
        }
        setCurrentPhase(Phase.DECIDE);
        DecideMessage decideMessage = new DecideMessage(this.viewNumber.get(), commitQC, this.replicaID);
        broadcastMessage(decideMessage);
    }

    // ==================== REPLICA METHODS ====================

    // prepare message from leader received
    public void onReceivePrepareMessage(PrepareMessage prepareMessage) {

        if (prepareMessage.getViewNumber() < this.viewNumber.get()) {
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
            authPerfLink.sendData(getLeader(prepareMessage.getViewNumber()), voteMessage);

            try {
                sendToSelf(voteMessage);
            } catch (Exception e) {
                System.err.println("Failed to handle own prepare vote message: " + e.getMessage());
            }
        }
    }

    public void onReceivePreCommitMessage(PreCommitMessage preCommitMessage) {
        if (preCommitMessage.getViewNumber() < this.viewNumber.get()) {
            System.err.println("Received pre-commit message for view " + preCommitMessage.getViewNumber() +
                    " but current view is " + this.viewNumber);
            return;
        }

        QuorumCertificate prepareQC = preCommitMessage.getPrepareQC();

        // verify QC is of the correct type for this phase
        if (!prepareQC.getType().equals("prepare")) {
            System.err.println("Pre-commit message has QC of wrong type: " + prepareQC.getType());
            return;
        }

        // verify the QC signature is legitimate
        String expectedMsg = createVoteMessage(HotStuffMessageType.PREPARE_VOTE, prepareQC.getViewNumber(),
                prepareQC.getNodeHash());
        if (!tverify(prepareQC.getSignatures(), expectedMsg, preCommitMessage.getSenderId())) {
            System.err.println("Pre-commit message has invalid QC signature, ignoring");
            return;
        }

        // check the node exists in our tree, leader can be byzantine
        Node node = tree.getNode(prepareQC.getNodeHash());
        if (node == null) {
            System.err.println("Pre-commit message references unknown node, ignoring");
            return;
        }

        // sign and send pre-commit vote to leader, sign over the hash of the proposal
        String partialSig = tsign(HotStuffMessageType.PRE_COMMIT_VOTE, this.viewNumber.get(), prepareQC.getNodeHash());
        PreCommitVoteMessage voteMessage = new PreCommitVoteMessage(this.viewNumber.get(), node, this.replicaID,
                partialSig);

        authPerfLink.sendData(getLeader(preCommitMessage.getViewNumber()), voteMessage);
        try {
            sendToSelf(voteMessage);
        } catch (Exception e) {
            System.err.println("Failed to handle own pre-commit vote message: " + e.getMessage());
        }
    }

    public void onReceiveCommitMessage(CommitMessage commitMessage) {
        if (commitMessage.getViewNumber() < this.viewNumber.get()) {
            System.err.println("Received commit message for view " + commitMessage.getViewNumber() +
                    " but current view is " + this.viewNumber);
            return;
        }

        QuorumCertificate preCommitQC = commitMessage.getPrecommitQC();

        // verify QC is of the correct type for this phase
        if (!preCommitQC.getType().equals("pre-commit")) {
            System.err.println("Commit message has QC of wrong type: " + preCommitQC.getType());
            return;
        }

        // verify the QC signature is legitimate
        String expectedMsg = createVoteMessage(HotStuffMessageType.PRE_COMMIT_VOTE, preCommitQC.getViewNumber(),
                preCommitQC.getNodeHash());
        if (!tverify(preCommitQC.getSignatures(), expectedMsg, commitMessage.getSenderId())) {
            System.err.println("Commit message has invalid QC signature, ignoring");
            return;
        }

        // check the node exists in our tree
        Node node = tree.getNode(preCommitQC.getNodeHash());
        if (node == null) {
            System.err.println("Commit message references unknown node, ignoring");
            return;
        }

        // TODO: where to use this lockedQC? how can we use it for safety checks?
        // this is where we lock
        // this means that we only accept proposals that extend from this node in future
        // views
        // even if they come with a valid QC
        tree.setLockedQC(preCommitQC);

        // sign and send commit vote to leader
        String partialSig = tsign(HotStuffMessageType.COMMIT_VOTE, this.viewNumber.get(), preCommitQC.getNodeHash());
        CommitVoteMessage voteMessage = new CommitVoteMessage(this.viewNumber.get(), node, this.replicaID, partialSig);

        authPerfLink.sendData(getLeader(commitMessage.getViewNumber()), voteMessage);
        try {
            sendToSelf(voteMessage);
        } catch (Exception e) {
            System.err.println("Failed to handle own commit vote message: " + e.getMessage());
        }
    }

    public void onReceiveDecideMessage(DecideMessage decideMessage) {
        if (decideMessage.getViewNumber() != this.viewNumber.get()) {
            System.err.println("Received decide message for view " + decideMessage.getViewNumber() +
                    " but current view is " + this.viewNumber);
            return;
        }

        QuorumCertificate commitQC = decideMessage.getCommitQC();

        // verify QC is of the correct type for this phase
        if (!commitQC.getType().equals("commit")) {
            System.err.println("Decide message has QC of wrong type: " + commitQC.getType());
            return;
        }

        // verify the QC signature is legitimate
        String expectedMsg = createVoteMessage(HotStuffMessageType.COMMIT_VOTE, commitQC.getViewNumber(),
                commitQC.getNodeHash());
        if (!tverify(commitQC.getSignatures(), expectedMsg, decideMessage.getSenderId())) {
            System.err.println("Decide message has invalid QC signature, ignoring");
            return;
        }

        // get the decided node
        Node decidedNode = tree.getNode(commitQC.getNodeHash());
        if (decidedNode == null) {
            System.err.println("Decide message references unknown node, ignoring");
            return;
        }

        // TODO: upcall to blockchain layer
        // for now we should just print the decided command or store in an append only
        // array/log
        System.out.println("Decided on command: " + decidedNode.getCommand());
        // onDecide(decidedNode);

        tree.pruneConflictingBranches(decidedNode);

        heartbeat();
        this.viewNumber.incrementAndGet();
        this.newViewNumber.set(this.viewNumber.get()); // Sync new view number with current view
        this.curProposal = null;

    }

    // ==================== TIMEOUT METHODS ====================

    public void heartbeat() {
        this.lastSeen = Instant.now();
        System.out.println("Heartbeat received.");
    }

    private void checkLeaderActivity() {
        Duration idleTime = Duration.between(this.lastSeen, Instant.now());

        if (!(idleTime.compareTo(TIMEOUT) > 0)) {
            onLeaderInactive();
        }
    }

    private void onLeaderInactive() {
        System.out.println("⚠ Leader is INACTIVE!");
        if (!amLeader(this.viewNumber.get())) {
            try {
                startNewView();
            } catch (Exception e) {
                System.err.println("Failed to start new view: " + e.getMessage());
            }
        }
    }

}