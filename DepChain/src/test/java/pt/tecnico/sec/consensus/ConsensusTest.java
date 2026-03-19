package pt.tecnico.sec.consensus;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import pt.tecnico.sec.router.MessageRouter;
import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.replica.ReplicaState;
import pt.tecnico.sec.utils.MemberManager;
import pt.tecnico.sec.utils.threshsig.Dealer;
import pt.tecnico.sec.utils.threshsig.GroupKey;
import pt.tecnico.sec.utils.threshsig.KeyShare;
import pt.tecnico.sec.utils.threshsig.SigShare;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Unit tests for the Basic HotStuff consensus algorithm.
 *
 * Sections:
 * 1. NEW_VIEW - quorum threshold and highest-QC selection
 * 2. Vote accumulation - full phase pipeline
 * 3. View change - leader crash, stale-message rejection
 * 4. Safety - conflicting node pruned after competing node is decided
 * 5. Byzantine - forged/tampered messages
 * 6. Future message buffering
 */
@ExtendWith(MockitoExtension.class)
class ConsensusTest {
    static class TestableConsensus extends Consensus {

        TestableConsensus(long replicaID, int totalReplicas, Consensus.CommandValidator commandValidator) {
            super(replicaID, totalReplicas, commandValidator);
        }

        @Override
        public String tsign(HotStuffMessageType type, int viewNumber, String nodeHash) {
            return "SIG:" + replicaID + ":" + type + ":" + viewNumber + ":" + nodeHash;
        }

        @Override
        public boolean tverify(String signaturesField, String message) {
            if (signaturesField == null)
                return false;
            return signaturesField.startsWith("SIG:");
        }

        /**
         * Bypass tcombine() - returns a fake but well-formed QC for phase transitions.
         */
        @Override
        public QuorumCertificate QC(Node node, java.util.Queue<HotStuffMessage> votes) {
            HotStuffMessage first = votes.peek();
            int viewNum = first != null ? first.getViewNumber() : viewNumber.get();
            String qcType = first instanceof PrepareVoteMessage ? "prepare"
                    : first instanceof PreCommitVoteMessage ? "pre-commit"
                            : first instanceof CommitVoteMessage ? "commit"
                                    : "unknown";
            return new QuorumCertificate(viewNum, node.getHash(), qcType, "SIG:fake-combined");
        }

        int getViewNumberInt() {
            return viewNumber.get();
        }

        Phase getPhase() {
            return getCurrentPhase();
        }

        /**
         * Simulates a view change (e.g. leader timeout) without triggering
         * startNewView() / drainFutureMessages(). Resets the per-view vote
         * flags exactly as onLeaderInactive() and onReceiveDecideMessage() do.
         */
        void simulateViewChange(int newView) {
            viewNumber.set(newView);
            prepareVoted = false;
            preCommitVoted = false;
            commitVoted = false;
            currentPhase = null;
        }

        /** Seeds the tree root so createLeaf() has a non-null parent. */
        void seedRoot(String rootHash) {
            Node root = new Node(rootHash, "genesis", null, null);
            tree.addNode(root);
            tree.setRoot(root);
        }
    }

    // -----------------------------------------------------------------------
    // Testing setup
    // -----------------------------------------------------------------------

    private static final int N = 4;
    private static final int QUORUM = N - (N - 1) / 3; // = 3

    @Mock
    MemberManager memberManager;
    @Mock
    ReplicaState replicaState;
    @Mock
    MessageRouter router;

    private TestableConsensus consensus(long replicaID) {
        TestableConsensus c = new TestableConsensus(replicaID, N, cmd -> true);
        c.setRouter(router);
        return c;
    }

    private NewViewMessage newView(int viewNum, long senderId) {
        return new NewViewMessage(viewNum, null, senderId);
    }

    private NewViewMessage newViewWithQC(int viewNum, long senderId, QuorumCertificate qc) {
        return new NewViewMessage(viewNum, qc, senderId);
    }

    private QuorumCertificate qc(int viewNum, String nodeHash, String type) {
        return new QuorumCertificate(viewNum, nodeHash, type, "SIG:combined");
    }

    // -----------------------------------------------------------------------
    // 1. NEW_VIEW collection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("1. NEW_VIEW collection")
    class NewViewCollection {

        @Test
        @DisplayName("PREPARE fires exactly at quorum, not before")
        void newView_quorumThreshold() throws Exception {
            // Replica 2 is leader for view 1
            TestableConsensus c = consensus(2);
            c.seedRoot("rootHash");
            when(router.getCommandtoPropose()).thenReturn("cmd");
            doNothing().when(router).sendConsensusMessage(any(), anyLong());

            c.onReceiveNewViewMessage(newView(1, 1));
            assertNull(c.getPhase(), "No prepare before quorum");

            c.onReceiveNewViewMessage(newView(1, 3));
            assertNull(c.getPhase(), "Still no prepare with quorum-1 messages");

            c.onReceiveNewViewMessage(newView(1, 4)); // quorum reached
            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
        }

        @Test
        @DisplayName("New proposal extends the node referenced by the highest QC, not a lower one")
        void newView_highestQCSelectedAsParent() throws Exception {
            // If replicas carry QCs from different views, the leader must pick the
            // highest-view QC as the parent for the new leaf - this is the liveness
            // requirement: the new proposal must extend the most recent locked node.
            TestableConsensus c = consensus(2);
            c.seedRoot("rootHash");

            Node lowNode = new Node("hash-low", "cmd", "rootHash", null);
            Node highNode = new Node("hash-high", "cmd", "rootHash", null);
            c.tree.addNode(lowNode);
            c.tree.addNode(highNode);

            when(router.getCommandtoPropose()).thenReturn("cmd");
            doNothing().when(router).sendConsensusMessage(any(), anyLong());

            // Sender 1 carries QC from view 1, sender 3 carries QC from view 5
            c.onReceiveNewViewMessage(newViewWithQC(1, 1, qc(1, "hash-low", "prepare")));
            c.onReceiveNewViewMessage(newViewWithQC(1, 3, qc(5, "hash-high", "prepare")));
            c.onReceiveNewViewMessage(newView(1, 4));

            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
            assertNotNull(c.curProposal);
            assertEquals("hash-high", c.curProposal.getParentHash(),
                    "New proposal must extend the node from the highest-view QC");
        }

        @Test
        @DisplayName("Duplicate NEW_VIEW from the same sender counts only once toward quorum")
        void newView_duplicateSenderDoesNotCountTwice() throws Exception {
            TestableConsensus c = consensus(2);
            c.seedRoot("rootHash");

            // Sender 1 sends the same NEW_VIEW twice
            c.onReceiveNewViewMessage(newView(1, 1));
            c.onReceiveNewViewMessage(newView(1, 1));

            // Only 1 distinct sender in the map - quorum not reached
            assertNull(c.getPhase());
        }
    }

    // -----------------------------------------------------------------------
    // 2. Vote accumulation - full phase pipeline
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("2. Vote accumulation")
    class VoteAccumulation {

        @Test
        @DisplayName("Full pipeline PREPARE -> PRE_COMMIT -> COMMIT -> DECIDE transitions correctly")
        void fullPipeline_allPhasesInOrder() throws Exception {
            TestableConsensus leader = consensus(2); // leader for view 1
            leader.viewNumber.set(1);
            doNothing().when(router).sendConsensusMessage(any(), anyLong());

            Node node = new Node("nodeHash", "cmd", "rootHash", null);
            leader.tree.addNode(node);

            leader.setCurrentPhase(Consensus.Phase.PREPARE);
            leader.curProposal = node;
            for (int s = 1; s <= QUORUM; s++)
                leader.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, node, s, "SIG:" + s));
            assertEquals(Consensus.Phase.PRE_COMMIT, leader.getPhase());

            for (int s = 1; s <= QUORUM; s++)
                leader.onReceivePreCommitVoteMessage(new PreCommitVoteMessage(1, node, s, "SIG:" + s));
            assertEquals(Consensus.Phase.COMMIT, leader.getPhase());

            for (int s = 1; s <= QUORUM; s++)
                leader.onReceiveCommitVoteMessage(new CommitVoteMessage(1, node, s, "SIG:" + s));
            assertEquals(Consensus.Phase.DECIDE, leader.getPhase());
        }

        @Test
        @DisplayName("Extra votes beyond quorum do not re-trigger a phase broadcast")
        void extraVotes_doNotCauseDoubleTransition() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(1);
            doNothing().when(router).sendConsensusMessage(any(), anyLong());

            Node node = new Node("nodeHash2", "cmd", "parentHash", null);
            c.tree.addNode(node);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            c.curProposal = node;

            // Send quorum + 2 extra votes
            for (int s = 1; s <= QUORUM + 2; s++)
                c.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, node, s, "SIG:" + s));

            // PRE_COMMIT broadcast must have happened exactly once (N-1 sends)
            verify(router, times(N - 1)).sendConsensusMessage(
                    argThat(m -> m instanceof PreCommitMessage), anyLong());
        }
    }

    // -----------------------------------------------------------------------
    // 3. View change - leader crash and stale-message rejection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("3. View change")
    class ViewChange {

        @Test
        @DisplayName("Late PREPARE votes for the old view are dropped after the view advances")
        void viewChange_staleVotesDroppedAfterViewAdvance() throws Exception {
            // Leader collects quorum in view 1, transitions to PRE_COMMIT, then view
            // advances to 2. A PREPARE vote still tagged view 1 must be silently dropped.
            TestableConsensus c = consensus(2);
            doNothing().when(router).sendConsensusMessage(any(), anyLong());

            Node node = new Node("nodeHash", "cmd", "root", null);
            c.tree.addNode(node);
            c.viewNumber.set(1);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            c.curProposal = node;

            for (int s = 1; s <= QUORUM; s++)
                c.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, node, s, "SIG:" + s));
            assertEquals(Consensus.Phase.PRE_COMMIT, c.getPhase());

            c.viewNumber.set(2);

            // Late vote for view 1 arrives
            c.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, node, 4, "SIG:4"));

            // Phase must stay PRE_COMMIT - late vote must not cause a spurious transition
            assertEquals(Consensus.Phase.PRE_COMMIT, c.getPhase());
        }

        @Test
        @DisplayName("Replica that moved to view 3 ignores a PREPARE from the crashed view 1 leader")
        void viewChange_replicaIgnoresPrepareFromCrashedLeader() {
            TestableConsensus c = consensus(4);
            c.viewNumber.set(3);

            Node oldNode = new Node("oldHash", "oldCmd", "root", null);
            PrepareMessage stale = new PrepareMessage(1, oldNode, null, 2);

            assertDoesNotThrow(() -> c.onReceivePrepareMessage(stale));
            verify(router, never()).sendConsensusMessage(any(), anyLong());
        }

        @Test
        @DisplayName("Stale votes for an old proposal cannot accumulate quorum after a new proposal is set")
        void viewChange_staleProposalCannotReachQuorum() throws Exception {
            // The leader has a new proposal (newNode) in view 2.
            // Quorum of votes for stale view 1 node must all be dropped,
            // so they cannot trigger a PRE_COMMIT for the stale node.
            TestableConsensus c = consensus(2);
            c.viewNumber.set(2);

            Node newNode = new Node("newHash", "newCmd", "root", null);
            c.tree.addNode(newNode);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            c.curProposal = newNode;

            Node staleNode = new Node("staleHash", "staleCmd", "root", null);

            for (int s = 1; s <= QUORUM; s++)
                c.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, staleNode, s, "SIG:" + s));

            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
        }
    }

    // -----------------------------------------------------------------------
    // 4. Safety - conflicting node pruned after competing node is decided
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("4. Safety - conflicting node pruned after competing node is decided")
    class SafetyConflict {

        /**
         * Scenario:
         * View 1 - Leader proposes node A (extends root). Replica accepts the PREPARE
         * and adds A to its local tree. Leader crashes - no quorum, A is never decided.
         *
         * View 2 - New leader proposes node B (also extends root, conflicts with A).
         * Replica goes through full PREPARE -> PRE_COMMIT -> COMMIT -> DECIDE for B.
         *
         * After DECIDE - A must be pruned from the local tree; B must still be present.
         */
        @Test
        @DisplayName("Node A pruned from local tree after conflicting node B is decided in the next view")
        void safety_conflictingNodePrunedAfterDecide() throws Exception {
            // Replica 4 is never a leader in views 1 or 2 - pure voter.
            // Leader view 1 = (1%4)+1 = 2; leader view 2 = (2%4)+1 = 3.
            TestableConsensus c = consensus(4);
            doNothing().when(router).sendConsensusMessage(any(), anyLong());

            // View 1: replica receives PREPARE for node A
            c.viewNumber.set(1);
            Node root = new Node("rootHash", "genesis", null, null);
            c.tree.addNode(root);
            c.tree.setRoot(root);

            Node nodeA = new Node("hashA", "cmdA", "rootHash", null);
            c.onReceivePrepareMessage(new PrepareMessage(1, nodeA, null, 2));
            assertNotNull(c.tree.getNode("hashA"), "Node A must be in tree after PREPARE");

            // Leader of view 1 crashes: replica times out, moves to view 2
            c.simulateViewChange(2);

            // View 2: new leader proposes node B (also extends root)
            Node nodeB = new Node("hashB", "cmdB", "rootHash", null);

            c.onReceivePrepareMessage(
                    new PrepareMessage(2, nodeB, qc(1, "rootHash", "prepare"), 3));
            assertNotNull(c.tree.getNode("hashB"), "Node B must be in tree after PREPARE");

            c.onReceivePreCommitMessage(
                    new PreCommitMessage(2, qc(2, "hashB", "prepare"), 3));

            c.onReceiveCommitMessage(
                    new CommitMessage(2, qc(2, "hashB", "pre-commit"), 3));

            c.onReceiveDecideMessage(
                    new DecideMessage(2, qc(2, "hashB", "commit"), 3));

            // Assertions
            verify(router).commandDecided("cmdB");

            assertNull(c.tree.getNode("hashA"),
                    "Node A (conflicting, never decided) must be pruned after B is decided");

            assertNotNull(c.tree.getNode("hashB"),
                    "Node B (decided) must remain in the tree");
        }
    }

    // -----------------------------------------------------------------------
    // 5. Byzantine - forged and tampered messages
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("5. Byzantine")
    class Byzantine {

        @Test
        @DisplayName("PRE_COMMIT for a node the replica never saw in PREPARE is rejected")
        void byzantine_preCommitWithUnknownNodeHash() {
            // Byzantine leader skips PREPARE and sends PRE_COMMIT for a node
            // the replica has never added to its tree. Node lookup returns null -> reject.
            TestableConsensus c = consensus(4);
            c.viewNumber.set(1);

            QuorumCertificate fakeQC = qc(1, "never-seen-hash", "prepare");
            PreCommitMessage msg = new PreCommitMessage(1, fakeQC, 2);

            assertDoesNotThrow(() -> c.onReceivePreCommitMessage(msg));
            verify(router, never()).sendConsensusMessage(any(), anyLong());
        }

        @Test
        @DisplayName("DECIDE with a forged QC signature is rejected - command not executed")
        void byzantine_decideWithForgedSignature() {
            // Byzantine leader broadcasts a DECIDE whose QC signature fails tverify.
            TestableConsensus c = consensus(4);
            c.viewNumber.set(1);

            Node node = new Node("hashX", "cmdX", "root", null);
            c.tree.addNode(node);

            // "FORGED" does not start with "SIG:" so tverify returns false
            QuorumCertificate badSigQC = new QuorumCertificate(1, "hashX", "commit", "FORGED");
            DecideMessage msg = new DecideMessage(1, badSigQC, 2);

            assertDoesNotThrow(() -> c.onReceiveDecideMessage(msg));
            verify(router, never()).commandDecided(any());
        }

        @Test
        @DisplayName("PREPARE that does not extend highQC and does not extend lockedQC is rejected")
        void byzantine_prepareFailsSafeNodeCheck() {
            // The locked node is nodeX. Byzantine leader proposes nodeY whose parent
            // is root (not nodeX), with a justifying QC of view 0 < lockedQC view 1.
            // Both branches of safeNode fail -> replica must not vote.
            TestableConsensus c = consensus(4);
            c.viewNumber.set(2);

            Node root = new Node("rootHash", "genesis", null, null);
            Node nodeX = new Node("hashX", "cmdX", "rootHash", null);
            c.tree.addNode(root);
            c.tree.setRoot(root);
            c.tree.addNode(nodeX);
            c.tree.setLockedQC(qc(1, "hashX", "pre-commit")); // locked at view 1

            // nodeY extends root, not nodeX, and its QC has view 0 < 1
            Node nodeY = new Node("hashY", "cmdY", "rootHash", null);
            PrepareMessage msg = new PrepareMessage(2, nodeY, qc(0, "rootHash", "prepare"), 3);

            assertDoesNotThrow(() -> c.onReceivePrepareMessage(msg));
            verify(router, never()).sendConsensusMessage(any(), anyLong());
        }

        @Test
        @DisplayName("Replayed DECIDE from an old view does not re-execute the command")
        void byzantine_replayedDecideIgnored() {
            // After a round completes the replica is at view 2.
            // A byzantine node replays the DECIDE from view 1.
            TestableConsensus c = consensus(4);
            c.viewNumber.set(2);

            Node node = new Node("hashOld", "cmdOld", "root", null);
            c.tree.addNode(node);

            DecideMessage replay = new DecideMessage(1, qc(1, "hashOld", "commit"), 2);
            c.onReceiveDecideMessage(replay);

            verify(router, never()).commandDecided(any());
            assertEquals(2, c.getViewNumberInt(), "View must not change on replayed DECIDE");
        }
    }

    // -----------------------------------------------------------------------
    // 6. Future message buffering
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("6. Future message buffering")
    class FutureMessageBuffering {

        @Test
        @DisplayName("Message from a future view is buffered and not processed immediately")
        void futureMessage_isBufferedNotProcessed() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(1);

            Node node = new Node("nodeHash", "cmd", "root", null);
            c.tree.addNode(node);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            c.curProposal = node;

            // Send a vote for view 2 while we are still in view 1
            PrepareVoteMessage futureVote = new PrepareVoteMessage(2, node, 1, "SIG:1");
            c.handleMessage(futureVote);

            // Phase must not have changed - the vote was buffered, not processed
            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
            assertNotNull(c.futureMessages.get(2), "Message should be buffered for view 2");
            assertEquals(1, c.futureMessages.get(2).size());
        }

        @Test
        @DisplayName("Buffered messages are replayed in protocol order when view advances")
        void futureMessage_replayedInOrderOnViewAdvance() throws Exception {
            TestableConsensus c = consensus(3); // leader for view 2
            c.viewNumber.set(1);

            Node node = new Node("nodeHash", "cmd", "root", null);
            c.tree.addNode(node);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            c.curProposal = node;

            // Buffer a full quorum of PREPARE votes for view 2 before we get there
            for (int s = 1; s <= QUORUM; s++) {
                c.handleMessage(new PrepareVoteMessage(2, node, s, "SIG:" + s));
            }

            // Still in view 1 - nothing should have been processed yet
            assertEquals(Consensus.Phase.PREPARE, c.getPhase());

            // Now advance to view 2 and set up state as if prepare was just broadcast
            c.viewNumber.set(2);
            c.curProposal = node;
            c.setCurrentPhase(Consensus.Phase.PREPARE);

            c.drainFutureMessages(2);

            // Buffered quorum of PREPARE votes should have triggered PRE_COMMIT
            assertEquals(Consensus.Phase.PRE_COMMIT, c.getPhase());
        }

        @Test
        @DisplayName("Messages beyond MAX_VIEW_LOOKAHEAD are dropped")
        void futureMessage_beyondLookaheadIsDropped() {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(1);

            Node node = new Node("nodeHash", "cmd", "root", null);
            c.tree.addNode(node);

            PrepareVoteMessage farFutureVote = new PrepareVoteMessage(1 + 100, node, 1, "SIG:1");
            c.handleMessage(farFutureVote);

            // Message should not be in the buffer
            assertNull(c.futureMessages.get(1 + 100));
        }

        @Test
        @DisplayName("Full round buffered in advance completes all the way to DECIDE when view advances")
        void futureMessage_fullRoundBufferedAndDecided() throws Exception {
            // Replica 4 is a pure voter (never leader in view 2, leader is 3)
            TestableConsensus c = consensus(4);
            c.viewNumber.set(1);
            doNothing().when(router).sendConsensusMessage(any(), anyLong());

            Node node = new Node("nodeHash", "cmd", "rootHash", null);
            c.tree.addNode(node);

            // While still in view 1, buffer all messages for view 2 out of order
            c.handleMessage(new DecideMessage(2, qc(2, "nodeHash", "commit"), 3));
            c.handleMessage(new CommitMessage(2, qc(2, "nodeHash", "pre-commit"), 3));
            c.handleMessage(new PreCommitMessage(2, qc(2, "nodeHash", "prepare"), 3));
            c.handleMessage(new PrepareMessage(2, node, qc(1, "nodeHash", "prepare"), 3));

            // All 4 messages buffered, nothing processed yet
            assertEquals(4, c.futureMessages.get(2).size());
            verify(router, never()).commandDecided(any());

            c.viewNumber.set(2);
            c.drainFutureMessages(2);

            verify(router).commandDecided("cmd");
        }
    }

    // -----------------------------------------------------------------------
    // 7. Threshold signatures - real crypto (tsign / tcombine / tverify)
    // -----------------------------------------------------------------------

    /**
     * Uses a subclass that loads keys injected in the constructor rather than
     * from disk, so these tests run without any config/keys directory.
     * tsign / tcombine / tverify are NOT overridden - they run real crypto.
     */
    @Nested
    @DisplayName("7. Threshold signatures")
    class ThresholdSignatures {
        private static final int L = 4;
        private static final int K = 3;

        private static GroupKey groupKey;
        private static KeyShare[] keyShares;

        @BeforeAll
        static void generateKeys() throws Exception {
            Dealer dealer = new Dealer(512);
            dealer.generateKeys(K, L);
            groupKey  = dealer.getGroupKey();
            KeyShare[] shares = dealer.getShares();
            keyShares = new KeyShare[L + 1];
            for (int i = 0; i < shares.length; i++) {
                keyShares[i + 1] = shares[i];
            }
        }

        class RealCryptoConsensus extends Consensus {
            RealCryptoConsensus(long replicaID, KeyShare privateKeyShare, GroupKey publicKey) {
                super(replicaID, L, cmd -> true);
                this.privateKeyShare = privateKeyShare;
                this.publicKey = publicKey;
            }
        }

        private RealCryptoConsensus replica(int id) {
            RealCryptoConsensus c = new RealCryptoConsensus(id, keyShares[id], groupKey);
            c.setRouter(router);
            return c;
        }

        // tsign

        @Test
        @DisplayName("tsign returns a non-null, non-empty base64 string")
        void tsign_returnsBase64() {
            RealCryptoConsensus c = replica(1);
            String sig = c.tsign(HotStuffMessageType.PREPARE_VOTE, 1, "someHash");
            assertNotNull(sig);
            assertFalse(sig.isEmpty());
            // Must be valid base64 (no exception on decode)
            assertDoesNotThrow(() -> Base64.getDecoder().decode(sig));
        }

        @Test
        @DisplayName("tsign produces different output for different vote types")
        void tsign_differentTypesProduceDifferentSigs() {
            RealCryptoConsensus c = replica(1);
            String sig1 = c.tsign(HotStuffMessageType.PREPARE_VOTE,    1, "hash");
            String sig2 = c.tsign(HotStuffMessageType.PRE_COMMIT_VOTE,  1, "hash");
            assertNotEquals(sig1, sig2);
        }

        @Test
        @DisplayName("tsign produces different output for different view numbers")
        void tsign_differentViewsProduceDifferentSigs() {
            RealCryptoConsensus c = replica(1);
            String sig1 = c.tsign(HotStuffMessageType.PREPARE_VOTE, 1, "hash");
            String sig2 = c.tsign(HotStuffMessageType.PREPARE_VOTE, 2, "hash");
            assertNotEquals(sig1, sig2);
        }

        // tcombine + tverify

        @Test
        @DisplayName("tcombine + tverify: k valid shares verify correctly")
        void tcombine_tverify_roundTrip() throws Exception {
            // Build a queue of k PrepareVoteMessages signed by replicas 1,2,3
            int view = 1;
            String nodeHash = "testNodeHash";
            String expectedMsg = buildVoteMessage(HotStuffMessageType.PREPARE_VOTE, view, nodeHash);

            java.util.Queue<HotStuffMessage> votes = buildVoteQueue(
                    HotStuffMessageType.PREPARE_VOTE, view, nodeHash, new int[]{1, 2, 3});

            RealCryptoConsensus leader = replica(1);
            byte[] msgBytes = expectedMsg.getBytes(StandardCharsets.UTF_8);

            String wBase64 = leader.tcombine(votes, msgBytes);

            assertNotNull(wBase64, "tcombine must return a non-null w");
            assertFalse(wBase64.isEmpty());

            assertTrue(
                leader.tverify(wBase64, expectedMsg),
                "tverify must accept the legitimately combined w"
            );
        }

        @Test
        @DisplayName("tverify rejects w combined from a different message")
        void tverify_rejectsWFromDifferentMessage() throws Exception {
            int view = 1;
            String nodeHash = "testNodeHash";

            // Combine over PREPARE_VOTE message
            String signedMsg = buildVoteMessage(HotStuffMessageType.PREPARE_VOTE, view, nodeHash);
            java.util.Queue<HotStuffMessage> votes = buildVoteQueue(
                    HotStuffMessageType.PREPARE_VOTE, view, nodeHash, new int[]{1, 2, 3});

            RealCryptoConsensus leader = replica(1);
            String wBase64 = leader.tcombine(votes, signedMsg.getBytes(StandardCharsets.UTF_8));

            // Verify against a DIFFERENT message (different view)
            String differentMsg = buildVoteMessage(HotStuffMessageType.PREPARE_VOTE, 99, nodeHash);
            assertFalse(
                leader.tverify(wBase64, differentMsg),
                "tverify must reject w that was combined over a different message"
            );
        }

        @Test
        @DisplayName("tverify rejects a forged (random) w value")
        void tverify_rejectsForgedW() {
            RealCryptoConsensus c = replica(1);
            String forgedW = Base64.getEncoder().encodeToString("FORGED".getBytes());
            String msg = buildVoteMessage(HotStuffMessageType.PREPARE_VOTE, 1, "hash");
            assertFalse(c.tverify(forgedW, msg));
        }

        @Test
        @DisplayName("tverify returns false for null w")
        void tverify_nullReturnsFalse() {
            RealCryptoConsensus c = replica(1);
            assertFalse(c.tverify(null, "anything"));
        }

        @Test
        @DisplayName("tverify returns false for empty w")
        void tverify_emptyReturnsFalse() {
            RealCryptoConsensus c = replica(1);
            assertFalse(c.tverify("", "anything"));
        }

        @Test
        @DisplayName("w combined by different leader replicas from the same votes verifies identically")
        void tcombine_deterministicAcrossLeaders() throws Exception {
            int view = 1;
            String nodeHash = "deterministicHash";
            String msg = buildVoteMessage(HotStuffMessageType.PREPARE_VOTE, view, nodeHash);
            byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);

            String w1 = replica(1).tcombine(
                buildVoteQueue(HotStuffMessageType.PREPARE_VOTE, view, nodeHash, new int[]{1,2,3}),
                msgBytes);
            String w2 = replica(2).tcombine(
                buildVoteQueue(HotStuffMessageType.PREPARE_VOTE, view, nodeHash, new int[]{1,2,3}),
                msgBytes);

            assertEquals(w1, w2, "Combining the same shares must yield the same w regardless of who combines");
        }

        @Test
        @DisplayName("w from PRE_COMMIT_VOTE shares does not verify against a PREPARE_VOTE message")
        void tverify_crossPhaseRejected() throws Exception {
            int view = 1;
            String nodeHash = "crossPhaseHash";

            // Combine over PRE_COMMIT_VOTE
            String preCommitMsg = buildVoteMessage(HotStuffMessageType.PRE_COMMIT_VOTE, view, nodeHash);
            java.util.Queue<HotStuffMessage> votes = buildVoteQueue(
                    HotStuffMessageType.PRE_COMMIT_VOTE, view, nodeHash, new int[]{1, 2, 3});

            RealCryptoConsensus leader = replica(1);
            String wBase64 = leader.tcombine(votes, preCommitMsg.getBytes(StandardCharsets.UTF_8));

            // Try to verify as PREPARE_VOTE
            String prepareMsg = buildVoteMessage(HotStuffMessageType.PREPARE_VOTE, view, nodeHash);
            assertFalse(
                leader.tverify(wBase64, prepareMsg),
                "w from PRE_COMMIT_VOTE must not verify against PREPARE_VOTE message"
            );
        }

        private String buildVoteMessage(HotStuffMessageType type, int view, String nodeHash) {
            return String.format("VOTE|type=%s|view=%d|node=%s", type, view, nodeHash);
        }

        private java.util.Queue<HotStuffMessage> buildVoteQueue(
                HotStuffMessageType type, int view, String nodeHash, int[] replicaIds)
                throws Exception {

            java.util.Queue<HotStuffMessage> queue = new java.util.LinkedList<>();
            Node node = new Node(nodeHash, "cmd", "parentHash", null);
            String msg = buildVoteMessage(type, view, nodeHash);
            byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);

            for (int id : replicaIds) {
                SigShare share = keyShares[id].sign(msgBytes);

                // Serialise share to base64 exactly as tsign() does
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                new java.io.ObjectOutputStream(bos).writeObject(share);
                String partialSigBase64 = Base64.getEncoder().encodeToString(bos.toByteArray());

                HotStuffMessage vote = switch (type) {
                    case PREPARE_VOTE    -> new PrepareVoteMessage(view, node, id, partialSigBase64);
                    case PRE_COMMIT_VOTE -> new PreCommitVoteMessage(view, node, id, partialSigBase64);
                    case COMMIT_VOTE     -> new CommitVoteMessage(view, node, id, partialSigBase64);
                    default -> throw new IllegalArgumentException("Unsupported vote type: " + type);
                };
                queue.add(vote);
            }
            return queue;
        }
    }
}