package pt.tecnico.sec.consensus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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

import pt.tecnico.sec.blockchain.Block;
import pt.tecnico.sec.blockchain.Blockchain;
import pt.tecnico.sec.blockchain.Transaction;
import pt.tecnico.sec.consensus.message.CommitMessage;
import pt.tecnico.sec.consensus.message.CommitVoteMessage;
import pt.tecnico.sec.consensus.message.DecideMessage;
import pt.tecnico.sec.consensus.message.HotStuffMessage;
import pt.tecnico.sec.consensus.message.HotStuffMessageType;
import pt.tecnico.sec.consensus.message.NewCommandMessage;
import pt.tecnico.sec.consensus.message.NewViewMessage;
import pt.tecnico.sec.consensus.message.PreCommitMessage;
import pt.tecnico.sec.consensus.message.PreCommitVoteMessage;
import pt.tecnico.sec.consensus.message.PrepareMessage;
import pt.tecnico.sec.consensus.message.PrepareVoteMessage;
import pt.tecnico.sec.router.MessageRouter;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.threshsig.GroupKey;
import pt.tecnico.sec.utils.threshsig.KeyShare;
import pt.tecnico.sec.utils.threshsig.SigShare;

public class Consensus {
    // current viewnumber
    AtomicInteger viewNumber;

    // my ID
    protected final long replicaID;

    // Quorum size
    protected final int quorumSize;

    // total number of replicas
    private final int totalReplicas;

    // router for message sending
    private MessageRouter router;

    // message handling

    // tree structure
    final Tree tree;

    // New-view message collection for leader
    private final Map<Long, NewViewMessage> newViewMessages = new ConcurrentHashMap<>();

    protected final Queue<PrepareVoteMessage> prepareVotes = new ConcurrentLinkedQueue<>();
    private final Queue<PreCommitVoteMessage> preCommitVotes = new ConcurrentLinkedQueue<>();
    private final Queue<CommitVoteMessage> commitVotes = new ConcurrentLinkedQueue<>();

    // Track which senders have already voted in each phase to reject duplicate
    // votes
    protected final java.util.Set<Long> prepareVoters = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> preCommitVoters = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> commitVoters = ConcurrentHashMap.newKeySet();

    // Messages from future views: key = view number, value = list of messages.
    final Map<Integer, List<HotStuffMessage>> futureMessages = new ConcurrentHashMap<>();

    // Cap how far ahead we buffer. Byzantine nodes could spam artificially high
    // view numbers to exhaust memory; this bounds the damage.
    private static final int MAX_VIEW_LOOKAHEAD = 10;

    public enum Phase {
        NEW_VIEW, PREPARE, PRE_COMMIT, COMMIT, DECIDE
    };

    Phase currentPhase;

    // will be used to check if the proposal is the same for all votes in the same
    // phase
    volatile Node curProposal = null;

    // tracks whether this replica has already voted in each phase for the current
    // view
    volatile boolean prepareVoted = false;
    volatile boolean preCommitVoted = false;
    volatile boolean commitVoted = false;

    ExecutorService executorService = java.util.concurrent.Executors.newFixedThreadPool(10);

    private volatile Instant lastSeen = Instant.now();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_WAIT_TIMEOUT = Duration.ofSeconds(12); // Timeout to wait for pending commands

    // Threshold signature keys
    protected KeyShare privateKeyShare;
    protected GroupKey publicKey;

    private static final String YELLOW = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    @FunctionalInterface
    public interface CommandValidator {
        boolean validate(String commandJson);
    }

    private final CommandValidator commandValidator;

    private void logHotStuff(String message) {
        System.out.println(YELLOW + "[HotStuff] " + message + RESET);
    }

    public Consensus(long replicaID, int totalReplicas, CommandValidator commandValidator) {
        this.replicaID = replicaID;
        this.totalReplicas = totalReplicas;
        this.quorumSize = totalReplicas - (totalReplicas - 1) / 3; // n-f where f = (n-1)/3
        this.tree = new Tree();
        this.viewNumber = new AtomicInteger(1);
        this.commandValidator = commandValidator;

        // Load threshold signature keys
        try {
            this.privateKeyShare = Crypto.loadThresholdPrivateKeyShare(
                    "config/keys/threshold_privateShare_" + replicaID + ".bin");
            this.publicKey = Crypto.loadThresholdPublicKey("config/keys/groupkey.bin");
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

    public void setRouter(MessageRouter router) {
        this.router = router;
    }

    protected MessageRouter getRouter() {
        return router;
    }

    protected int getTotalReplicas() {
        return totalReplicas;
    }

    // ==================== COMMON UTILITY METHODS ====================

    public boolean amLeader(int viewNumber) {
        return (viewNumber % this.totalReplicas) + 1 == this.replicaID;
    }

    public int getLeader(int viewNumber) {
        return (viewNumber % this.totalReplicas) + 1;
    }

    public boolean isLeader(int viewNumber, long senderId) {
        return senderId == getLeader(viewNumber);
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
        NewViewMessage newViewMessage = new NewViewMessage(this.viewNumber.get(), tree.getPrepareQC(),
                this.replicaID);
        if (amLeader(this.viewNumber.get())) {
            logHotStuff("I am leader for next view, sending message to myself");
            newViewMessages.put(this.replicaID, newViewMessage);
        } else if (router != null) {
            logHotStuff("Sending message to leader of next view");
            router.sendConsensusMessage(newViewMessage, getLeader(this.viewNumber.get()));
        } else {
            // Fallback for tests - use AuthPerfLink directly
            System.out.println("No router set, cannot send new-view message to leader");
        }
        heartbeat();
    }

    public void resetFlagsForNewView() {
        this.prepareVoted = false;
        this.preCommitVoted = false;
        this.commitVoted = false;
    }

    // ==================== FUTURE MESSAGE BUFFERING ====================

    // stores the message in the futureMessages map to be processed when we advance
    // to that view number
    private void bufferFutureMessage(HotStuffMessage msg) {
        int msgView = msg.getViewNumber();
        int currentView = this.viewNumber.get();

        if (msgView > currentView + MAX_VIEW_LOOKAHEAD) {
            return;
        }

        // If no list exists for this view yet, create one, then append the message
        futureMessages.computeIfAbsent(msgView, k -> new ArrayList<>()).add(msg);
    }

    // called when we advance to a new view number, processes any buffered messages
    // for that view
    void drainFutureMessages(int view) {
        List<HotStuffMessage> pending = futureMessages.remove(view);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        // Sort by phase order so messages are replayed in protocol sequence
        // (PREPARE -> PRE_COMMIT -> COMMIT -> DECIDE) even if they arrived out of order
        pending.sort(Comparator.comparingInt(m -> phaseOrder(m.getHotStuffType())));

        for (HotStuffMessage msg : pending) {
            handleMessage(msg);
        }
    }

    // ==================== MESSAGE DISPATCH ====================

    public void handleMessage(HotStuffMessage msg) {
        try {
            int msgView = msg.getViewNumber();
            int currentView = this.viewNumber.get();

            // Future message: buffer and return
            if (msgView > currentView) {
                bufferFutureMessage(msg);
                return;
            }

            // Stale message: discard (except NEW_VIEW which the leader accepts from
            // the previous view number while it waits to collect a quorum)
            if (msgView < currentView) {
                if (msg.getHotStuffType() != HotStuffMessageType.NEW_VIEW) {
                    System.out.println("Discarding stale " + msg.getHotStuffType()
                            + " for view " + msgView + " (current=" + currentView + ")");
                    return;
                }
            }

            // Current view: normal dispatch
            logHotStuff("Received " + msg.getHotStuffType() + " from replica " + msg.getSenderId() + " (view " + msgView
                    + ")");
            switch (msg.getHotStuffType()) {
                case NEW_COMMAND -> onReceiveNewCommandMessage((NewCommandMessage) msg);
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
        for (int i = 1; i < this.totalReplicas + 1; i++) {
            if (i != this.replicaID) {
                final int replicaIndex = i;
                executorService.submit(() -> {
                    try {
                        if (router != null) {
                            router.sendConsensusMessage(message, replicaIndex);
                        } else {
                            // Fallback for tests - use AuthPerfLink directly
                            router.sendConsensusMessage(message, replicaIndex);
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to send message to replica " + replicaIndex + ": " + e.getMessage());
                    }
                });
            }
        }
        sendToSelf(message);
    }

    public void sendToSelf(HotStuffMessage message) throws Exception {
        if (amLeader(message.getViewNumber())) {
            handleMessage(message);
        }
    }

    // ==================== CRYPTOGRAPHIC OPERATIONS ====================

    public QuorumCertificate QC(Node node, Queue<HotStuffMessage> votes) {
        try {
            HotStuffMessage firstVote = votes.peek();
            int viewNum = firstVote != null ? firstVote.getViewNumber() : this.viewNumber.get();
            String qcType = firstVote instanceof PrepareVoteMessage ? "prepare"
                    : firstVote instanceof PreCommitVoteMessage ? "pre-commit"
                            : firstVote instanceof CommitVoteMessage ? "commit" : "unknown";

            HotStuffMessageType voteType = firstVote instanceof PrepareVoteMessage ? HotStuffMessageType.PREPARE_VOTE
                    : firstVote instanceof PreCommitVoteMessage ? HotStuffMessageType.PRE_COMMIT_VOTE
                            : HotStuffMessageType.COMMIT_VOTE;

            String message = createVoteMessage(voteType, viewNum, node.getHash());
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            String wBase64 = tcombine(votes, messageBytes);
            if (wBase64 == null)
                return null;

            return new QuorumCertificate(viewNum, node.getHash(), qcType, wBase64);

        } catch (Exception e) {
            System.err.println("Failed to create QC: " + e.getMessage());
            return null;
        }
    }

    public String tcombine(Queue<HotStuffMessage> votes, byte[] messageBytes) {
        try {
            SigShare[] sigShares = new SigShare[votes.size()];
            int i = 0;
            for (HotStuffMessage vote : votes) {
                String partialSigBase64 = extractPartialSig(vote);
                if (partialSigBase64 == null)
                    continue;

                byte[] sigBytes = Base64.getDecoder().decode(partialSigBase64);
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(sigBytes));
                sigShares[i++] = (SigShare) ois.readObject();
            }

            BigInteger w = Crypto.combineShares(sigShares, this.quorumSize, this.totalReplicas,
                    this.publicKey, messageBytes);
            return Base64.getEncoder().encodeToString(w.toByteArray());

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

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            new ObjectOutputStream(bos).writeObject(sigShare);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            System.err.println("Failed to create threshold signature: " + e.getMessage());
            return null;
        }
    }

    public boolean tverify(String wBase64, String message) {
        try {
            if (wBase64 == null || wBase64.isEmpty())
                return false;

            BigInteger w = new BigInteger(Base64.getDecoder().decode(wBase64));
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            return Crypto.verifyThresholdSignature(messageBytes, w, this.totalReplicas, this.publicKey);
        } catch (Exception e) {
            System.err.println("Failed to verify threshold signature: " + e.getMessage());
            return false;
        }
    }

    // ==================== HELPER METHODS ====================

    private boolean safeNode(Node node, QuorumCertificate justify) {
        QuorumCertificate lockedQC = tree.getLockedQC();

        // genesis QC - always safe to extend from
        if (lockedQC.getViewNumber() == 0)
            return true;

        if (justify != null && justify.getViewNumber() > lockedQC.getViewNumber()) {
            return true;
        }

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

        return new Node(nodeHash, command, parent.getHash(), null);
    }

    private String extractPartialSig(HotStuffMessage vote) {
        if (vote instanceof PrepareVoteMessage v)
            return v.getPartialSig();
        if (vote instanceof PreCommitVoteMessage v)
            return v.getPartialSig();
        if (vote instanceof CommitVoteMessage v)
            return v.getPartialSig();
        return null;
    }

    private boolean checkView(HotStuffMessage message, int viewNumber) {
        if (message.getViewNumber() < viewNumber) {
            System.out.println("Received message for view " + message.getViewNumber() +
                    " but current view is " + viewNumber);
            return false;
        }
        return true;
    }

    /**
     * Gets a command to propose with timeout mechanism.
     * First tries to get an immediate command, then waits for a timeout period
     * checking periodically for new commands. Returns "NO-OP" if no command
     * becomes available within the timeout.
     */
    private String getCommandWithTimeout() {
        Block block;

        // If a block is full, propose immediately
        block = router.getBlocktoPropose();
        if (block != null && isBlockFull(block)) {
            System.out.println("PROPOSING FULL BLOCK IMMEDIATELY");
            return block.toJson();
        }

        // Propose once per timeout interval
        System.out.println("WAITING " + COMMAND_WAIT_TIMEOUT.toMillis() + "ms before proposing a block");

        long timeoutMs = COMMAND_WAIT_TIMEOUT.toMillis();
        try {
            Thread.sleep(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Command wait interrupted: " + e.getMessage());
            return "NO-OP";
        }

        block = router.getBlocktoPropose();
        if (block != null) {
            System.out.println("    Proposing block after timeout");
            return block.toJson();
        }

        System.out.println("    NO COMMAND AVAILABLE, PROPOSING NO-OP");
        return "NO-OP";
    }

    private boolean isBlockFull(Block block) {
        if (block == null) {
            return false;
        }
        long totalGas = 0;
        for (Transaction tx : block.getTransactions()) {
            totalGas += tx.getGasLimit();
            if (totalGas >= Blockchain.BLOCK_GAS_LIMIT) {
                return true;
            }
        }
        return false;
    }

    private boolean checkisLeaderMessage(HotStuffMessage message, int viewNumber) {
        if (!isLeader(message.getViewNumber(), message.getSenderId())) {
            System.out.println("Received message from sender " + message.getSenderId() +
                    " but current leader for view " + message.getViewNumber() + " is "
                    + getLeader(message.getViewNumber()));
            return false;
        }
        return true;
    }

    private int phaseOrder(HotStuffMessageType type) {
        return switch (type) {
            case NEW_VIEW -> 0;
            case NEW_COMMAND -> 1;
            case PREPARE -> 2;
            case PREPARE_VOTE -> 3;
            case PRE_COMMIT -> 4;
            case PRE_COMMIT_VOTE -> 5;
            case COMMIT -> 6;
            case COMMIT_VOTE -> 7;
            case DECIDE -> 8;
            default -> 99;
        };
    }

    // ==================== LEADER METHODS ====================

    // Leader collects new-view messages and moves to prepare phase
    public synchronized QuorumCertificate onReceiveNewViewMessage(NewViewMessage newViewMessage) throws Exception {

        if (newViewMessage.getViewNumber() < this.viewNumber.get()) {
            System.err.println("Received new-view message for view " + newViewMessage.getViewNumber() +
                    " but current view is " + this.viewNumber);
            return null;
        }

        if (!amLeader(newViewMessage.getViewNumber())) {
            System.err.println("Received new-view message for view " + newViewMessage.getViewNumber() +
                    " but am not Leader for next view");
            return null;
        }

        newViewMessages.put(newViewMessage.getSenderId(), newViewMessage);

        // if received (n-f) new-view messages
        if (newViewMessages.size() >= this.quorumSize) {
            logHotStuff("View " + newViewMessage.getViewNumber() + " - NEW_VIEW quorum reached, advancing to PREPARE");
            // choose highest prepareQC from collected new-view messages
            QuorumCertificate highQC = getHighestQC(newViewMessages.values().stream().toList());

            int newView = newViewMessage.getViewNumber();
            this.viewNumber.set(newView);

            // clear collected new-view messages for this view
            newViewMessages.clear();
            resetFlagsForNewView();

            String command = getCommandWithTimeout();
            prepare(highQC, command);
            return highQC;
        }

        return null;
    }

    // prepare phase - leader creates new leaf, broadcasts prepare message with new
    // leaf and highQC
    public void prepare(QuorumCertificate highQC, String command) throws Exception {
        if (!amLeader(this.viewNumber.get())) {
            return;
        }

        setCurrentPhase(Phase.PREPARE);
        // logHotStuff("View " + this.viewNumber.get() + " - PREPARE phase started (command: " + command + ")");

        // if highQC is null, then this is the very first proposal and the new node
        // should extend from the root
        Node parentNode = (highQC != null) ? tree.getNode(highQC.getNodeHash()) : tree.getRoot();

        Node newNode = createLeaf(parentNode, command);
        this.curProposal = newNode;
        tree.addNode(newNode);

        // broadcast prepare message
        PrepareMessage prepareMessage = new PrepareMessage(this.viewNumber.get(), newNode, highQC, this.replicaID);
        this.prepareVotes.clear();
        this.prepareVoters.clear();
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

        if (!prepareVoters.add(voteMessage.getSenderId())) {
            System.err.println("Duplicate PREPARE_VOTE from replica " + voteMessage.getSenderId() + ", ignoring");
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
            logHotStuff("View " + this.viewNumber.get() + " - PREPARE phase complete");
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
        logHotStuff("View " + this.viewNumber.get() + " - PRE-COMMIT phase started");
        // Broadcast prepareQC in pre-commit messages
        PreCommitMessage preCommitMessage = new PreCommitMessage(this.viewNumber.get(), prepareQC, this.replicaID);
        this.preCommitVotes.clear();
        this.preCommitVoters.clear();
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

        if (!preCommitVoters.add(voteMessage.getSenderId())) {
            System.err.println("Duplicate PRE_COMMIT_VOTE from replica " + voteMessage.getSenderId() + ", ignoring");
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
            logHotStuff("View " + this.viewNumber.get() + " - PRE-COMMIT phase complete");
            commit(preCommitQC);
        }
    }

    // commit phase
    public void commit(QuorumCertificate preCommitQC) throws Exception {
        if (!amLeader(preCommitQC.getViewNumber())) {
            return;
        }
        setCurrentPhase(Phase.COMMIT);
        logHotStuff("View " + this.viewNumber.get() + " - COMMIT phase started");
        CommitMessage commitMessage = new CommitMessage(this.viewNumber.get(), preCommitQC, this.replicaID);
        this.commitVotes.clear();
        this.commitVoters.clear();
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

        if (!commitVoters.add(voteMessage.getSenderId())) {
            System.err.println("Duplicate COMMIT_VOTE from replica " + voteMessage.getSenderId() + ", ignoring");
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
            logHotStuff("View " + this.viewNumber.get() + " - COMMIT phase complete");
            decide(commitQC);
        }
    }

    // decide phase
    public void decide(QuorumCertificate commitQC) throws Exception {
        if (!amLeader(commitQC.getViewNumber())) {
            return;
        }
        setCurrentPhase(Phase.DECIDE);
        logHotStuff("View " + this.viewNumber.get() + " - DECIDE phase started");
        DecideMessage decideMessage = new DecideMessage(this.viewNumber.get(), commitQC, this.replicaID);
        broadcastMessage(decideMessage);
    }

    // ==================== REPLICA METHODS ====================

    public void onReceiveNewCommandMessage(NewCommandMessage newCommandMessage) throws Exception {
        // Only leader can receive new command messages from clients
        if (!amLeader(newCommandMessage.getViewNumber())) {
            System.err.println("Received new command message but am not leader, ignoring");
            return;
        }
        // for now we assume that just the leader will receive client commands
        try {
            prepare(null, newCommandMessage.getCommand());
        } catch (Exception e) {
            System.err.println("Failed to start prepare phase for new command: " + e.getMessage());
        }
    }

    // prepare message from leader received
    public void onReceivePrepareMessage(PrepareMessage prepareMessage) {
        if (!checkView(prepareMessage, this.viewNumber.get())
                || !checkisLeaderMessage(prepareMessage, this.viewNumber.get())) {
            return;
        }

        // Check command validity
        if (!validCommand(prepareMessage.getNode().getCommand())) {
            logHotStuff("Invalid command detected in PREPARE, starting new view");
            try {
                // if we receive a prepare message with an invalid command,
                // the leader is probably byzantine and injected a command, so we trigger a view
                // change
                // System.err.println("Received PREPARE with invalid command: " + prepareMessage.getNode().getCommand());
                onLeaderInactive();
            } catch (Exception e) {
                System.err.println("Failed to start new view after detecting invalid command: " + e.getMessage());
            }
            return;
        }

        QuorumCertificate highQC = prepareMessage.getHighQC();
        Node ancestorNode;
        if (highQC == null) {
            ancestorNode = tree.getRoot();
        } else {
            if (!highQC.getType().equals("prepare")) {
                System.err.println("Prepare message has highQC of wrong type: " + highQC.getType());
                return;
            }

            // verify the QC signature is legitimate
            String expectedMsg = createVoteMessage(HotStuffMessageType.PREPARE_VOTE, highQC.getViewNumber(),
                    highQC.getNodeHash());
            if (!tverify(highQC.getW(), expectedMsg)) {
                System.err.println("Prepare message has invalid QC signature, ignoring");
                onLeaderInactive();
                return;
            }
            ancestorNode = tree.getNode(highQC.getNodeHash());
        }

        if (tree.extendsFrom(prepareMessage.getNode(), ancestorNode)
                && safeNode(prepareMessage.getNode(), prepareMessage.getHighQC())) {

            // prevent duplicate vote
            if (prepareVoted) {
                System.err.println("Already sent PREPARE_VOTE in view " + this.viewNumber.get()
                        + ", ignoring second PREPARE");
                return;
            }

            tree.addNode(prepareMessage.getNode());
            prepareVoted = true;
            String partialSig = tsign(HotStuffMessageType.PREPARE_VOTE, this.viewNumber.get(),
                    prepareMessage.getNode().getHash());

            // send prepare vote with partial signature to leader
            PrepareVoteMessage voteMessage = new PrepareVoteMessage(this.viewNumber.get(), prepareMessage.getNode(),
                    this.replicaID, partialSig);

            // update view
            this.viewNumber.set(prepareMessage.getViewNumber());
            if (amLeader(prepareMessage.getViewNumber())) {
                try {
                    sendToSelf(voteMessage);
                } catch (Exception e) {
                    System.err.println("Failed to handle own prepare vote message: " + e.getMessage());
                }
            } else {
                // Send vote to leader
                if (router != null) {
                    router.sendConsensusMessage(voteMessage, getLeader(prepareMessage.getViewNumber()));
                }
            }
        } else {
            logHotStuff("INVALID PROPOSAL FOR PREPARE");
        }
    }

    public void onReceivePreCommitMessage(PreCommitMessage preCommitMessage) {
        if (!checkView(preCommitMessage, this.viewNumber.get())
                || !checkisLeaderMessage(preCommitMessage, this.viewNumber.get())) {
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
        if (!tverify(prepareQC.getW(), expectedMsg)) {
            System.err.println("Pre-commit message has invalid QC signature, ignoring");
            onLeaderInactive();
            return;
        }

        // check the node exists in our tree, leader can be byzantine
        Node node = tree.getNode(prepareQC.getNodeHash());
        if (node == null) {
            System.err.println("Pre-commit message references unknown node, ignoring");
            return;
        }

        // prevent duplicate vote
        if (preCommitVoted) {
            System.err.println("Already sent PRE_COMMIT_VOTE in view " + this.viewNumber.get()
                    + ", ignoring second PRE_COMMIT");
            return;
        }

        tree.setPrepareQC(prepareQC);
        preCommitVoted = true;

        // sign and send pre-commit vote to leader, sign over the hash of the proposal
        String partialSig = tsign(HotStuffMessageType.PRE_COMMIT_VOTE, this.viewNumber.get(), prepareQC.getNodeHash());
        PreCommitVoteMessage voteMessage = new PreCommitVoteMessage(this.viewNumber.get(), node, this.replicaID,
                partialSig);

        if (amLeader(preCommitMessage.getViewNumber())) {
            try {
                sendToSelf(voteMessage);
            } catch (Exception e) {
                System.err.println("Failed to handle own pre-commit vote message: " + e.getMessage());
            }
        } else {
            if (router != null) {
                router.sendConsensusMessage(voteMessage, getLeader(preCommitMessage.getViewNumber()));
            }
        }
    }

    public void onReceiveCommitMessage(CommitMessage commitMessage) {
        if (!checkView(commitMessage, this.viewNumber.get())
                || !checkisLeaderMessage(commitMessage, this.viewNumber.get())) {
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
        if (!tverify(preCommitQC.getW(), expectedMsg)) {
            System.err.println("Commit message has invalid QC signature, ignoring");
            onLeaderInactive();
            return;
        }

        // check the node exists in our tree
        Node node = tree.getNode(preCommitQC.getNodeHash());
        if (node == null) {
            System.err.println("Commit message references unknown node, ignoring");
            return;
        }

        // prevent duplicate vote
        if (commitVoted) {
            System.err.println("Already sent COMMIT_VOTE in view " + this.viewNumber.get()
                    + ", ignoring second COMMIT");
            return;
        }

        tree.setLockedQC(preCommitQC);
        commitVoted = true;

        // sign and send commit vote to leader
        String partialSig = tsign(HotStuffMessageType.COMMIT_VOTE, this.viewNumber.get(), preCommitQC.getNodeHash());
        CommitVoteMessage voteMessage = new CommitVoteMessage(this.viewNumber.get(), node, this.replicaID, partialSig);

        if (amLeader(commitMessage.getViewNumber())) {
            try {
                sendToSelf(voteMessage);
            } catch (Exception e) {
                System.err.println("Failed to handle own commit vote message: " + e.getMessage());
            }
        } else {
            if (router != null) {
                router.sendConsensusMessage(voteMessage, getLeader(commitMessage.getViewNumber()));
            }
        }
    }

    public void onReceiveDecideMessage(DecideMessage decideMessage) {
        if (!checkView(decideMessage, this.viewNumber.get())
                || !checkisLeaderMessage(decideMessage, this.viewNumber.get())) {
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
        if (!tverify(commitQC.getW(), expectedMsg)) {
            System.err.println("Decide message has invalid QC signature, ignoring");
            onLeaderInactive();
            return;
        }

        // get the decided node
        Node decidedNode = tree.getNode(commitQC.getNodeHash());
        if (decidedNode == null) {
            System.err.println("Decide message references unknown node, ignoring");
            return;
        }

        // logHotStuff("View " + decideMessage.getViewNumber() + " - DECIDED on command: " + decidedNode.getCommand());

        tree.pruneConflictingBranches(decidedNode);

        heartbeat();
        int nextView = this.viewNumber.incrementAndGet();
        this.curProposal = null;
        resetFlagsForNewView();

        router.blockDecided(decidedNode.getCommand());

        // Drain buffered messages for the new view BEFORE sending NEW_VIEW
        drainFutureMessages(nextView);

        try {
            startNewView();
        } catch (Exception e) {
            System.err.println("Failed to start new view after decide: " + e.getMessage());
        }
    }

    // ==================== TIMEOUT METHODS ====================

    public void heartbeat() {
        this.lastSeen = Instant.now();
    }

    private void checkLeaderActivity() {
        Duration idleTime = Duration.between(this.lastSeen, Instant.now());

        if (idleTime.compareTo(TIMEOUT) > 0) { // trigger when idle > timeout
            onLeaderInactive();
        }
    }

    private void onLeaderInactive() {
        int nextView = this.viewNumber.incrementAndGet();
        resetFlagsForNewView();
        try {
            logHotStuff("⚠ Leader is INACTIVE/FAULTY! Starting new view " + nextView);
            drainFutureMessages(nextView);
            startNewView();
        } catch (Exception e) {
            System.err.println("Failed to start new view: " + e.getMessage());
        }
    }

    private boolean validCommand(String command) {
        if (command.equals("NO-OP")) {
            logger("Received NO-OP command, treating as valid");
            return true;
        }

        if (!commandValidator.validate(command)) {
            System.err.println("Command validation failed in PREPARE phase");
            return false;
        }
        return true;
    }

    private void logger(String message) {
        // print in blue color
        System.out.println("\u001B[34m" + message + "\u001B[0m");
    }
}