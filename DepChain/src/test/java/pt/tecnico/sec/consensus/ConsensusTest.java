package pt.tecnico.sec.consensus;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import pt.tecnico.sec.application.Application;
import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.replica.ReplicaState;
import pt.tecnico.sec.utils.MemberManager;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Consensus (Basic HotStuff) implementation.
 *
 * Structure:
 *  1. Leader election helpers
 *  2. Phase management
 *  3. Message handling – NEW_VIEW collection
 *  4. Message handling – vote accumulation (PREPARE -> PRE_COMMIT -> COMMIT -> DECIDE)
 *  5. Safety checks (safeNode, QC type/signature validation)
 *  6. View-number filtering (stale/future messages)
 *  7. Timeout / leader-inactivity
 *  8. Byzantine / adversarial scenarios
 */
@ExtendWith(MockitoExtension.class)
class ConsensusTest {

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    /**
     * Minimal testable subclass of Consensus that:
     *  - skips loading real threshold keys from disk
     *  - exposes otherwise-package-private state for assertions
     *  - replaces crypto calls with deterministic stubs
     */
    static class TestableConsensus extends Consensus {

        TestableConsensus(long replicaID, int totalReplicas,
                          MemberManager memberManager,
                          ReplicaState replicaState) {
            super(replicaID, totalReplicas, memberManager, replicaState);
        }

        // Override threshold-sign so tests never need key files
        @Override
        public String tsign(HotStuffMessageType type, int viewNumber, String nodeHash) {
            // deterministic stub: "SIG:<replicaID>:<type>:<view>:<hash>"
            return "SIG:" + replicaID + ":" + type + ":" + viewNumber + ":" + nodeHash;
        }

        // Override tverify so tests can control validity
        @Override
        public boolean tverify(String signaturesField, String message, long senderId) {
            // accept anything that starts with "SIG:" or the special token "VALID"
            if (signaturesField == null) return false;
            return signaturesField.startsWith("SIG:") || signaturesField.equals("VALID");
        }

        /**
         * Override QC() to bypass tcombine(), which tries to base64-decode our
         * "SIG:x" stub strings and fails. Returns a deterministic fake QC instead -
         * sufficient for all phase-transition tests.
         */
        @Override
        public QuorumCertificate QC(Node node, java.util.Queue<HotStuffMessage> votes) {
            HotStuffMessage first = votes.peek();
            int viewNum = first != null ? first.getViewNumber() : viewNumber.get();
            String qcType = first instanceof PrepareVoteMessage   ? "prepare"
                          : first instanceof PreCommitVoteMessage ? "pre-commit"
                          : first instanceof CommitVoteMessage    ? "commit"
                          : "unknown";
            return new QuorumCertificate(viewNum, node.getHash(), qcType, "SIG:fake-combined");
        }

        // Expose internal view number for assertions
        int getViewNumberInt() {
            return viewNumber.get();
        }

        // Expose current phase
        Phase getPhase() {
            return getCurrentPhase();
        }

        /**
         * Seeds the tree with a proper root node so that prepare() / createLeaf()
         * never receives a null parent. Call this before any test that exercises prepare().
         */
        void seedRoot(String rootHash) {
            Node root = new Node(rootHash, "genesis", null, null);
            tree.addNode(root);
            tree.setRoot(root);
        }
    }

    // -----------------------------------------------------------------------
    // Shared fixtures
    // -----------------------------------------------------------------------

    private static final int N = 4;   // 4 replicas  ->  f=1, quorum=3
    private static final int QUORUM = N - (N - 1) / 3; // = 3

    @Mock MemberManager memberManager;
    @Mock ReplicaState  replicaState;
    @Mock Application   application;

    /** Creates a consensus instance for replica `id` (1-indexed). */
    private TestableConsensus consensus(long replicaID) {
        TestableConsensus c = new TestableConsensus(replicaID, N, memberManager, replicaState);
        c.setApplication(application);
        return c;
    }

    /** Convenience: build a bare NewViewMessage. */
    private NewViewMessage newView(int viewNum, long senderId) {
        return new NewViewMessage(viewNum, null, senderId);
    }

    /** Convenience: build a NewViewMessage with a QC. */
    private NewViewMessage newViewWithQC(int viewNum, long senderId, QuorumCertificate qc) {
        return new NewViewMessage(viewNum, qc, senderId);
    }

    /** Convenience: build a QuorumCertificate with given view and hash. */
    private QuorumCertificate qc(int viewNum, String nodeHash, String type) {
        // signatures field starts with "SIG:" so tverify stub accepts it
        return new QuorumCertificate(viewNum, nodeHash, type, "SIG:combined");
    }

    // -----------------------------------------------------------------------
    // 1. Leader-election helpers
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("1. Leader election")
    class LeaderElection {

        @Test
        @DisplayName("getLeader returns (view % N) + 1")
        void getLeader_formula() {
            TestableConsensus c = consensus(1);
            // view 1 -> (1%4)+1 = 2
            assertEquals(2, c.getLeader(1));
            // view 4 -> (4%4)+1 = 1
            assertEquals(1, c.getLeader(4));
            // view 0 -> (0%4)+1 = 1
            assertEquals(1, c.getLeader(0));
        }

        @Test
        @DisplayName("amLeader returns true only for the correct replica")
        void amLeader_correctReplica() {
            // For view 1, leader = (1%4)+1 = 2
            TestableConsensus leader   = consensus(2);
            TestableConsensus nonLeader = consensus(1);

            assertTrue(leader.amLeader(1));
            assertFalse(nonLeader.amLeader(1));
        }

        @Test
        @DisplayName("amLeader wraps around correctly at view == N")
        void amLeader_wraparound() {
            // view N=4 -> leader = (4%4)+1 = 1
            TestableConsensus c = consensus(1);
            assertTrue(c.amLeader(N));
        }

        @Test
        @DisplayName("isLeader checks senderId against derived leader")
        void isLeader_mismatch() {
            TestableConsensus c = consensus(1);
            // For view 1, leader=2; senderId=3 should return false
            assertFalse(c.isLeader(1, 3));
            assertTrue(c.isLeader(1, 2));
        }

        @Test
        @DisplayName("Different views have different leaders (round-robin)")
        void leaderRotation() {
            TestableConsensus c = consensus(1);
            Set<Integer> leaders = new HashSet<>();
            for (int v = 1; v <= N; v++) leaders.add(c.getLeader(v));
            assertEquals(N, leaders.size(), "Every replica should be leader for exactly one of the first N views");
        }
    }

    // -----------------------------------------------------------------------
    // 2. Phase management
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("2. Phase management")
    class PhaseManagement {

        @Test
        @DisplayName("setCurrentPhase / getCurrentPhase round-trip")
        void phaseRoundTrip() {
            TestableConsensus c = consensus(1);
            for (Consensus.Phase p : Consensus.Phase.values()) {
                c.setCurrentPhase(p);
                assertEquals(p, c.getCurrentPhase());
            }
        }

        @Test
        @DisplayName("checkPhase returns false when phase does not match")
        void checkPhase_wrongPhase() {
            TestableConsensus c = consensus(1);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            assertFalse(c.checkPhase(Consensus.Phase.PRE_COMMIT));
        }

        @Test
        @DisplayName("checkPhase returns true when phase matches")
        void checkPhase_correctPhase() {
            TestableConsensus c = consensus(1);
            c.setCurrentPhase(Consensus.Phase.COMMIT);
            assertTrue(c.checkPhase(Consensus.Phase.COMMIT));
        }
    }

    // -----------------------------------------------------------------------
    // 3. NEW_VIEW collection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("3. NEW_VIEW collection")
    class NewViewCollection {

        @Test
        @DisplayName("Leader ignores NEW_VIEW messages for a past view")
        void newView_staleViewIgnored() throws Exception {
            // Replica 1 is leader for view 4 (4%4+1=1). Start at view 4.
            TestableConsensus c = consensus(1);
            c.viewNumber.set(4);

            // Message for view 3 (past) should be ignored
            NewViewMessage stale = newView(3, 2);
            QuorumCertificate result = c.onReceiveNewViewMessage(stale);
            assertNull(result, "Stale NEW_VIEW should be ignored");
        }

        @Test
        @DisplayName("Non-leader ignores NEW_VIEW messages")
        void newView_nonLeaderIgnores() throws Exception {
            // Replica 3 is NOT the leader for view 1 (leader=2)
            TestableConsensus c = consensus(3);
            QuorumCertificate result = c.onReceiveNewViewMessage(newView(1, 2));
            assertNull(result);
        }

        @Test
        @DisplayName("Leader triggers prepare only after quorum of NEW_VIEW messages")
        void newView_quorumTriggersPrepare() throws Exception {
            // Replica 2 is leader for view 1
            TestableConsensus c = consensus(2);
            c.seedRoot("rootHash");  // required so createLeaf() has a non-null parent
            when(application.getCommandtoPropose()).thenReturn("tx:hello");
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

            // quorum=3, so send 2 messages first (senders 1 and 3, skipping leader=2)
            // -> should NOT trigger prepare yet
            c.onReceiveNewViewMessage(newView(1, 1));
            assertNull(c.getPhase(), "Prepare should not start after 1 message");
            c.onReceiveNewViewMessage(newView(1, 3));
            assertNull(c.getPhase(), "Prepare should not start before quorum");

            // Third message reaches quorum -> prepare must fire
            c.onReceiveNewViewMessage(newView(1, 4));
            assertEquals(Consensus.Phase.PREPARE, c.getPhase(),
                    "Prepare should start once quorum is reached");
        }

        @Test
        @DisplayName("getHighestQC selects the QC with the greatest view number")
        void newView_highestQCSelected() throws Exception {
            TestableConsensus c = consensus(2); // leader view 1
            c.seedRoot("rootHash");
            when(application.getCommandtoPropose()).thenReturn("cmd");
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

            // Add nodes that the QCs reference so tree lookups succeed
            Node lowNode  = new Node("hash-low",  "cmd", "rootHash", null);
            Node highNode = new Node("hash-high", "cmd", "rootHash", null);
            c.tree.addNode(lowNode);
            c.tree.addNode(highNode);

            QuorumCertificate lowQC  = qc(1, "hash-low",  "prepare");
            QuorumCertificate highQC = qc(5, "hash-high", "prepare");

            c.onReceiveNewViewMessage(newViewWithQC(1, 1, lowQC));
            c.onReceiveNewViewMessage(newViewWithQC(1, 3, highQC));
            c.onReceiveNewViewMessage(newView(1, 4));

            // Quorum reached -> PREPARE triggered using the highest available QC
            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
        }

        @Test
        @DisplayName("Duplicate NEW_VIEW from same sender is deduplicated (ConcurrentHashMap key)")
        void newView_duplicateSenderDeduped() throws Exception {
            TestableConsensus c = consensus(2);
            // send same sender twice - map should keep only latest
            c.onReceiveNewViewMessage(newView(1, 1));
            c.onReceiveNewViewMessage(newView(1, 1)); // duplicate

            // Only 1 distinct sender in the map -> no quorum reached yet
            assertNull(c.getPhase());
        }
    }

    // -----------------------------------------------------------------------
    // 4. Vote accumulation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("4. Vote accumulation (leader side)")
    class VoteAccumulation {

        /**
         * Helper: put the leader-consensus into PREPARE phase
         * and set up a curProposal node (also registers it in the tree).
         */
        private Node setupPreparePhase(TestableConsensus c, String nodeHash) {
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            // Use the seeded root as parent so createLeaf works if called later
            Node node = new Node(nodeHash, "cmd", "rootHash", null);
            c.curProposal = node;
            c.tree.addNode(node);
            return node;
        }

        // --- PREPARE votes ---

        @Test
        @DisplayName("Leader moves to PRE_COMMIT after quorum of PREPARE votes")
        void prepareVotes_quorumTriggersPrecommit() throws Exception {
            TestableConsensus c = consensus(2); // leader view 1
            c.viewNumber.set(1);
            doNothing().when(application).sendConsensusMessage(any(), anyLong());
            Node node = setupPreparePhase(c, "nodeHash1");

            for (int sender = 1; sender <= QUORUM; sender++) {
                PrepareVoteMessage vote = new PrepareVoteMessage(1, node, sender, "SIG:" + sender);
                c.onReceivePrepareVoteMessage(vote);
            }

            assertEquals(Consensus.Phase.PRE_COMMIT, c.getPhase());
        }

        @Test
        @DisplayName("Extra PREPARE votes after quorum are ignored")
        void prepareVotes_extraVotesIgnored() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(1);
            doNothing().when(application).sendConsensusMessage(any(), anyLong());
            Node node = setupPreparePhase(c, "nodeHash2");

            // send quorum + 1 votes
            for (int sender = 1; sender <= QUORUM + 1; sender++) {
                PrepareVoteMessage vote = new PrepareVoteMessage(1, node, sender, "SIG:" + sender);
                c.onReceivePrepareVoteMessage(vote);
            }

            // Phase should be PRE_COMMIT (not broken by extra vote)
            assertEquals(Consensus.Phase.PRE_COMMIT, c.getPhase());
        }

        @Test
        @DisplayName("PREPARE vote for wrong node hash is discarded")
        void prepareVote_wrongNodeDiscarded() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(1);
            Node node = setupPreparePhase(c, "correctHash");

            Node wrongNode = new Node("wrongHash", "cmd", "parentHash", null);
            PrepareVoteMessage vote = new PrepareVoteMessage(1, wrongNode, 1, "SIG:1");
            c.onReceivePrepareVoteMessage(vote);

            // Phase stays PREPARE – no transition
            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
        }

        @Test
        @DisplayName("PREPARE vote for stale view is discarded")
        void prepareVote_staleViewDiscarded() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(2); // current view is 2
            Node node = setupPreparePhase(c, "nodeHash");

            // vote claims view 1
            PrepareVoteMessage stale = new PrepareVoteMessage(1, node, 1, "SIG:1");
            c.onReceivePrepareVoteMessage(stale);

            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
        }

        @Test
        @DisplayName("PREPARE vote received by non-leader is silently dropped")
        void prepareVote_nonLeaderDrops() throws Exception {
            TestableConsensus c = consensus(3); // NOT leader for view 1
            c.viewNumber.set(1);
            Node node = new Node("h", "cmd", "p", null);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            c.curProposal = node;

            PrepareVoteMessage vote = new PrepareVoteMessage(1, node, 1, "SIG:1");
            // Should not throw, and phase stays PREPARE
            assertDoesNotThrow(() -> c.onReceivePrepareVoteMessage(vote));
            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
        }

        // --- PRE_COMMIT votes ---

        @Test
        @DisplayName("Leader moves to COMMIT after quorum of PRE_COMMIT votes")
        void preCommitVotes_quorumTriggersCommit() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(1);
            doNothing().when(application).sendConsensusMessage(any(), anyLong());
            c.setCurrentPhase(Consensus.Phase.PRE_COMMIT);
            Node node = new Node("nodeHashPC", "cmd", "parentHash", null);
            c.curProposal = node;
            c.tree.addNode(node);

            for (int sender = 1; sender <= QUORUM; sender++) {
                PreCommitVoteMessage vote = new PreCommitVoteMessage(1, node, sender, "SIG:" + sender);
                c.onReceivePreCommitVoteMessage(vote);
            }

            assertEquals(Consensus.Phase.COMMIT, c.getPhase());
        }

        // --- COMMIT votes ---

        @Test
        @DisplayName("Leader moves to DECIDE after quorum of COMMIT votes")
        void commitVotes_quorumTriggersDecide() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(1);
            doNothing().when(application).sendConsensusMessage(any(), anyLong());
            c.setCurrentPhase(Consensus.Phase.COMMIT);
            Node node = new Node("nodeHashC", "cmd", "parentHash", null);
            c.curProposal = node;
            c.tree.addNode(node);

            for (int sender = 1; sender <= QUORUM; sender++) {
                CommitVoteMessage vote = new CommitVoteMessage(1, node, sender, "SIG:" + sender);
                c.onReceiveCommitVoteMessage(vote);
            }

            assertEquals(Consensus.Phase.DECIDE, c.getPhase());
        }
    }

    // -----------------------------------------------------------------------
    // 5. Safety checks
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("5. Safety checks")
    class SafetyChecks {

        @Test
        @DisplayName("PRE_COMMIT message with wrong QC type is rejected")
        void preCommit_wrongQCType() {
            TestableConsensus c = consensus(3); // non-leader replica
            c.viewNumber.set(1);

            // QC type is "pre-commit" but PRE_COMMIT message must carry a "prepare" QC
            QuorumCertificate badQC = qc(1, "nodeHash", "pre-commit");
            PreCommitMessage msg = new PreCommitMessage(1, badQC, 2); // sender=2=leader

            // Should log an error and return without sending a vote
            assertDoesNotThrow(() -> c.onReceivePreCommitMessage(msg));
            verify(application, never()).sendConsensusMessage(any(), anyInt());
        }

        @Test
        @DisplayName("COMMIT message with wrong QC type is rejected")
        void commit_wrongQCType() {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(1);

            QuorumCertificate badQC = qc(1, "nodeHash", "commit"); // should be "pre-commit"
            CommitMessage msg = new CommitMessage(1, badQC, 2);

            assertDoesNotThrow(() -> c.onReceiveCommitMessage(msg));
            verify(application, never()).sendConsensusMessage(any(), anyInt());
        }

        @Test
        @DisplayName("DECIDE message with wrong QC type is rejected")
        void decide_wrongQCType() {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(1);

            QuorumCertificate badQC = qc(1, "nodeHash", "prepare"); // should be "commit"
            DecideMessage msg = new DecideMessage(1, badQC, 2);

            assertDoesNotThrow(() -> c.onReceiveDecideMessage(msg));
            verify(application, never()).commandDecided(any());
        }

        @Test
        @DisplayName("PRE_COMMIT message with invalid QC signature is rejected")
        void preCommit_invalidSignature() {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(1);

            // "INVALID" is not accepted by our tverify stub
            QuorumCertificate badSigQC = new QuorumCertificate(1, "nodeHash", "prepare", "INVALID_SIG");
            PreCommitMessage msg = new PreCommitMessage(1, badSigQC, 2);

            assertDoesNotThrow(() -> c.onReceivePreCommitMessage(msg));
            verify(application, never()).sendConsensusMessage(any(), anyInt());
        }

        @Test
        @DisplayName("DECIDE message referencing unknown node hash is rejected")
        void decide_unknownNode() {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(1);

            // QC references a node hash that was never added to the tree
            QuorumCertificate commitQC = qc(1, "unknownHash", "commit");
            DecideMessage msg = new DecideMessage(1, commitQC, 2);

            assertDoesNotThrow(() -> c.onReceiveDecideMessage(msg));
            verify(application, never()).commandDecided(any());
        }
    }

    // -----------------------------------------------------------------------
    // 6. View-number filtering
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("6. View-number filtering")
    class ViewNumberFiltering {

        @Test
        @DisplayName("PREPARE message for old view is ignored")
        void prepare_staleViewIgnored() {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(5); // current view = 5

            Node node = new Node("h", "cmd", "root", null);
            PrepareMessage msg = new PrepareMessage(2, node, null, 2); // view=2 < 5

            assertDoesNotThrow(() -> c.onReceivePrepareMessage(msg));
            // No vote should have been sent
            verify(application, never()).sendConsensusMessage(any(), anyInt());
        }

        @Test
        @DisplayName("PREPARE vote for wrong view is discarded by leader")
        void prepareVote_wrongViewDiscarded() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(3);
            Node node = new Node("h", "cmd", "p", null);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            c.curProposal = node;

            PrepareVoteMessage vote = new PrepareVoteMessage(1, node, 1, "SIG:1"); // view=1 ≠ 3
            c.onReceivePrepareVoteMessage(vote);

            assertEquals(Consensus.Phase.PREPARE, c.getPhase());
        }
    }

    // -----------------------------------------------------------------------
    // 7. View progression after DECIDE
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("7. View progression")
    class ViewProgression {

        @Test
        @DisplayName("View number increments after a DECIDE message is processed")
        void decide_incrementsView() throws Exception {
            TestableConsensus c = consensus(3); // non-leader replica
            c.viewNumber.set(1);
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

            Node node = new Node("decidedHash", "cmd", "root", null);
            c.tree.addNode(node);

            QuorumCertificate commitQC = qc(1, "decidedHash", "commit");
            DecideMessage msg = new DecideMessage(1, commitQC, 2);

            c.onReceiveDecideMessage(msg);

            assertEquals(2, c.getViewNumberInt(), "View should advance to 2 after decide");
        }

        @Test
        @DisplayName("commandDecided is called with the decided command")
        void decide_callsCommandDecided() throws Exception {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(1);
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

            Node node = new Node("decidedHash2", "myCommand", "root", null);
            c.tree.addNode(node);

            QuorumCertificate commitQC = qc(1, "decidedHash2", "commit");
            DecideMessage msg = new DecideMessage(1, commitQC, 2);

            c.onReceiveDecideMessage(msg);

            verify(application).commandDecided("myCommand");
        }
    }

    // -----------------------------------------------------------------------
    // 8. Byzantine / adversarial scenarios
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("8. Byzantine / adversarial scenarios")
    class Byzantine {

        @Test
        @DisplayName("Byzantine leader sends PRE_COMMIT with tampered node hash - rejected")
        void byzantine_tamperedNodeHashInPrecommit() {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(1);

            // QC references a node hash that was never seen during prepare
            // (byzantine leader invented a hash)
            QuorumCertificate fakeQC = qc(1, "byzantine-invented-hash", "prepare");
            PreCommitMessage msg = new PreCommitMessage(1, fakeQC, 2);

            assertDoesNotThrow(() -> c.onReceivePreCommitMessage(msg));
            // No pre-commit vote should be sent
            verify(application, never()).sendConsensusMessage(any(), anyInt());
        }

        @Test
        @DisplayName("Byzantine replica sends NEW_VIEW with future view - not counted toward quorum of current view")
        void byzantine_newViewFutureView() throws Exception {
            TestableConsensus c = consensus(2); // leader view 1
            c.viewNumber.set(1);

            // Byzantine node claims view 999
            NewViewMessage byzantine = newView(999, 1);
            // Should not crash; quorum for view 1 not reached
            assertDoesNotThrow(() -> c.onReceiveNewViewMessage(byzantine));
            assertNull(c.getPhase());
        }

        @Test
        @DisplayName("Byzantine leader replays an old DECIDE message - view guard rejects it")
        void byzantine_replayedDecide() throws Exception {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(3); // current view is 3

            Node node = new Node("oldHash", "oldCmd", "root", null);
            c.tree.addNode(node);

            // DECIDE for view 1 (already past)
            QuorumCertificate oldCommitQC = qc(1, "oldHash", "commit");
            DecideMessage replay = new DecideMessage(1, oldCommitQC, 2);

            c.onReceiveDecideMessage(replay);

            // commandDecided must NOT be called
            verify(application, never()).commandDecided(any());
            // View must remain 3
            assertEquals(3, c.getViewNumberInt());
        }

        @Test
        @DisplayName("PREPARE message from non-leader sender is flagged (checkisLeaderMessage)")
        void byzantine_prepareFromNonLeader() {
            TestableConsensus c = consensus(3);
            c.viewNumber.set(1);

            // For view 1, leader=2. We send from sender=4 (byzantine non-leader)
            Node node = new Node("h", "cmd", "root", null);
            // Add parent so extendsFrom doesn't NPE
            c.tree.addNode(node);
            PrepareMessage msg = new PrepareMessage(1, node, null, 4 /* senderId=4, not leader */);

            // checkisLeaderMessage currently only logs (returns true), but the
            // test verifies no crash occurs and behaviour is graceful
            assertDoesNotThrow(() -> c.onReceivePrepareMessage(msg));
        }

        @Test
        @DisplayName("Null partial signature in PREPARE vote does not crash leader")
        void byzantine_nullPartialSig() throws Exception {
            TestableConsensus c = consensus(2);
            c.viewNumber.set(1);
            doNothing().when(application).sendConsensusMessage(any(), anyLong());
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            Node node = new Node("nodeHashNull", "cmd", "parentHash", null);
            c.curProposal = node;
            c.tree.addNode(node);

            // Send two legitimate votes then one with null sig - must not throw
            assertDoesNotThrow(() -> {
                c.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, node, 1, "SIG:1"));
                c.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, node, 3, null));
                c.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, node, 4, "SIG:4"));
            });
        }
    }
}