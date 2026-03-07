package pt.tecnico.sec.consensus;

import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.router.MessageRouter;

public class ByzantineConsensus extends Consensus {

    // Drop every outgoing broadcast message (silent/crash-failure simulation)
    private volatile boolean silent = false;
    
    // When acting as leader, send two conflicting PREPARE proposals: the first half
    // of replicas receives the original node, the second half receives an
    // alternative node with a different command and hash
    private volatile boolean sendConflicting = false;
    
    // When voting (PREPARE_VOTE / PRE_COMMIT_VOTE / COMMIT_VOTE), sign and send a
    // vote for a fabricated node hash rather than the one proposed by the leader
    private volatile boolean voteForWrongNode = false;
    
    // Send vote messages to a non-leader replica instead of the actual leader
    // The protocol should reject votes arriving at non-leader replicas
    private volatile boolean sendToWrongLeader = false;
    
    // Immediately re-send every vote message after the first send (double-voting / vote-replay attack)
    // The protocol should handle duplicate votes gracefully
    private volatile boolean doubleVote = false;

    private static final String ORANGE   = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    // CONSTRUCTOR AND SETTERS

    public ByzantineConsensus(long replicaID, int totalReplicas) {
        super(replicaID, totalReplicas);
    }

    private void logByzantine(String msg) {
        System.out.println(ORANGE + "[Byzantine] " + msg + RESET);
    }

    public void setSilent(boolean value) {
        this.silent = value;
        logByzantine("silent = " + value);
    }

    public void setSendConflicting(boolean value) {
        this.sendConflicting = value;
        logByzantine("sendConflicting = " + value);
    }

    public void setVoteForWrongNode(boolean value) {
        this.voteForWrongNode = value;
        logByzantine("voteForWrongNode = " + value);
    }

    public void setSendToWrongLeader(boolean value) {
        this.sendToWrongLeader = value;
        logByzantine("sendToWrongLeader = " + value);
    }

    public void setDoubleVote(boolean value) {
        this.doubleVote = value;
        logByzantine("doubleVote = " + value);
    }

    // BYZANTINE OVERRIDES

    @Override
    public void handleMessage(HotStuffMessage msg) {
        if (silent) {
            logByzantine("Dropping incoming " + msg.getHotStuffType()
                    + " from replica " + msg.getSenderId());
            return;
        }
        super.handleMessage(msg);
    }

    @Override
    public void broadcastMessage(HotStuffMessage message) throws Exception {
        if (silent) {
            logByzantine("Dropping outgoing " + message.getHotStuffType()
                    + " (silent)");
            return;
        }

        if (sendConflicting && message instanceof PrepareMessage prepareMsg) {
            logByzantine("Sending conflicting PREPARE proposals (view "
                    + prepareMsg.getViewNumber() + ")");
            broadcastConflictingPrepare(prepareMsg);
            return;
        }

        super.broadcastMessage(message);

        if (doubleVote && isVoteMessage(message)) {
            logByzantine("Double-voting: re-sending " + message.getHotStuffType());
            super.broadcastMessage(message);
        }
    }

    @Override
    public void onReceivePrepareMessage(PrepareMessage prepareMessage) {
        if (!voteForWrongNode && !sendToWrongLeader) {
            super.onReceivePrepareMessage(prepareMessage);
            return;
        }

        // Ignore stale messages
        if (prepareMessage.getViewNumber() < viewNumber.get()) return;

        QuorumCertificate highQC = prepareMessage.getHighQC();
        Node ancestorNode = (highQC == null) ? tree.getRoot() : tree.getNode(highQC.getNodeHash());

        if (!tree.extendsFrom(prepareMessage.getNode(), ancestorNode)
                || !safeNodeInternal(prepareMessage.getNode(), highQC)) {
            return;
        }

        tree.addNode(prepareMessage.getNode());
        viewNumber.set(prepareMessage.getViewNumber());

        String nodeHash = voteForWrongNode
                ? "BYZANTINE_WRONG_NODE_VIEW_" + viewNumber.get()
                : prepareMessage.getNode().getHash();
        Node voteNode = voteForWrongNode
                ? new Node(nodeHash, "BYZANTINE", prepareMessage.getNode().getHash(), null)
                : prepareMessage.getNode();

        String partialSig = tsign(HotStuffMessageType.PREPARE_VOTE, viewNumber.get(), nodeHash);
        PrepareVoteMessage vote = new PrepareVoteMessage(
                viewNumber.get(), voteNode, replicaID, partialSig);

        int target = sendToWrongLeader
                ? getWrongLeader(prepareMessage.getViewNumber())
                : getLeader(prepareMessage.getViewNumber());

        logByzantine("PREPARE_VOTE: " + (voteForWrongNode ? "WRONG hash" : "correct hash")
                + " -> replica " + target);

        dispatchVote(vote, prepareMessage.getViewNumber(), target);
    }

    @Override
    public void onReceivePreCommitMessage(PreCommitMessage preCommitMessage) {
        if (!voteForWrongNode && !sendToWrongLeader) {
            super.onReceivePreCommitMessage(preCommitMessage);
            return;
        }

        if (preCommitMessage.getViewNumber() < viewNumber.get()) return;

        QuorumCertificate prepareQC = preCommitMessage.getPrepareQC();
        Node node = tree.getNode(prepareQC.getNodeHash());
        if (!voteForWrongNode && node == null) {
            System.err.println("[Byzantine] Pre-commit references unknown node, ignoring");
            return;
        }

        tree.setPrepareQC(prepareQC);

        String nodeHash = voteForWrongNode
                ? "BYZANTINE_WRONG_NODE_VIEW_" + viewNumber.get()
                : prepareQC.getNodeHash();
        Node voteNode = voteForWrongNode
                ? new Node(nodeHash, "BYZANTINE", prepareQC.getNodeHash(), null)
                : node;

        String partialSig = tsign(HotStuffMessageType.PRE_COMMIT_VOTE, viewNumber.get(), nodeHash);
        PreCommitVoteMessage vote = new PreCommitVoteMessage(
                viewNumber.get(), voteNode, replicaID, partialSig);

        int target = sendToWrongLeader
                ? getWrongLeader(preCommitMessage.getViewNumber())
                : getLeader(preCommitMessage.getViewNumber());

        logByzantine("PRE_COMMIT_VOTE: " + (voteForWrongNode ? "WRONG hash" : "correct hash")
                + " -> replica " + target);

        dispatchVote(vote, preCommitMessage.getViewNumber(), target);
    }

    @Override
    public void onReceiveCommitMessage(CommitMessage commitMessage) {
        if (!voteForWrongNode && !sendToWrongLeader) {
            super.onReceiveCommitMessage(commitMessage);
            return;
        }

        if (commitMessage.getViewNumber() < viewNumber.get()) return;

        QuorumCertificate preCommitQC = commitMessage.getPrecommitQC();
        Node node = tree.getNode(preCommitQC.getNodeHash());
        if (!voteForWrongNode && node == null) {
            System.err.println("[Byzantine] Commit references unknown node, ignoring");
            return;
        }

        tree.setLockedQC(preCommitQC);

        String nodeHash = voteForWrongNode
                ? "BYZANTINE_WRONG_NODE_VIEW_" + viewNumber.get()
                : preCommitQC.getNodeHash();
        Node voteNode = voteForWrongNode
                ? new Node(nodeHash, "BYZANTINE", preCommitQC.getNodeHash(), null)
                : node;

        String partialSig = tsign(HotStuffMessageType.COMMIT_VOTE, viewNumber.get(), nodeHash);
        CommitVoteMessage vote = new CommitVoteMessage(
                viewNumber.get(), voteNode, replicaID, partialSig);

        int target = sendToWrongLeader
                ? getWrongLeader(commitMessage.getViewNumber())
                : getLeader(commitMessage.getViewNumber());

        logByzantine("COMMIT_VOTE: " + (voteForWrongNode ? "WRONG hash" : "correct hash")
                + " -> replica " + target);

        dispatchVote(vote, commitMessage.getViewNumber(), target);
    }

    // HELPERS

    private void broadcastConflictingPrepare(PrepareMessage original) throws Exception {
        Node origNode = original.getNode();

        // Alternative node: same parent, different command and hash
        Node altNode = new Node(
                "BYZANTINE_ALT_" + origNode.getHash(),
                origNode.getCommand() + "_CONFLICTING",
                origNode.getParentHash(),
                null);
        tree.addNode(altNode);

        PrepareMessage altMsg = new PrepareMessage(
                original.getViewNumber(), altNode, original.getHighQC(), replicaID);

        int total = getTotalReplicas();
        int half  = total / 2;
        MessageRouter r = getRouter();

        for (int i = 1; i <= total; i++) {
            if (i == replicaID) continue;
            final int targetReplica = i;
            final PrepareMessage toSend = (targetReplica <= half) ? original : altMsg;
            executorService.submit(() -> {
                try {
                    if (r != null) r.sendConsensusMessage(toSend, targetReplica);
                } catch (Exception e) {
                    System.err.println("[Byzantine] Conflicting send failed to "
                            + targetReplica + ": " + e.getMessage());
                }
            });
        }
        // The leader itself always uses the original proposal internally
        sendToSelf(original);
    }

    private boolean safeNodeInternal(Node node, QuorumCertificate justify) {
        return true;
    }

    private void dispatchVote(HotStuffMessage vote, int viewNum, int target) {
        if (amLeader(viewNum)) {
            try { sendToSelf(vote); } catch (Exception e) { /* ignore */ }
        } else {
            MessageRouter r = getRouter();
            if (r != null) r.sendConsensusMessage(vote, target);
        }
    }

    private int getWrongLeader(int viewNum) {
        int correctLeader = getLeader(viewNum);
        return (correctLeader % getTotalReplicas()) + 1;
    }

    private boolean isVoteMessage(HotStuffMessage msg) {
        return msg instanceof PrepareVoteMessage
                || msg instanceof PreCommitVoteMessage
                || msg instanceof CommitVoteMessage;
    }
}
