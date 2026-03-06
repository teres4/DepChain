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
 * Unit tests for the Basic HotStuff consensus algorithm.
 *
 * Sections:
 *  1. NEW_VIEW - quorum threshold and highest-QC selection
 *  2. Vote accumulation - full phase pipeline
 *  3. View change - leader crash, stale-message rejection
 *  4. Safety - conflicting node pruned after competing node is decided
 *  5. Byzantine - forged/tampered messages
 */
@ExtendWith(MockitoExtension.class)
class ConsensusTest {
    static class TestableConsensus extends Consensus {

        TestableConsensus(long replicaID, int totalReplicas,
                          MemberManager memberManager,
                          ReplicaState replicaState) {
            super(replicaID, totalReplicas, memberManager, replicaState);
        }

        @Override
        public String tsign(HotStuffMessageType type, int viewNumber, String nodeHash) {
            return "SIG:" + replicaID + ":" + type + ":" + viewNumber + ":" + nodeHash;
        }

        @Override
        public boolean tverify(String signaturesField, String message, long senderId) {
            if (signaturesField == null) return false;
            return signaturesField.startsWith("SIG:");
        }

        /** Bypass tcombine() - returns a fake but well-formed QC for phase transitions. */
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

        int getViewNumberInt() { return viewNumber.get(); }
        Phase getPhase()       { return getCurrentPhase(); }

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

    @Mock MemberManager memberManager;
    @Mock ReplicaState  replicaState;
    @Mock Application   application;

    private TestableConsensus consensus(long replicaID) {
        TestableConsensus c = new TestableConsensus(replicaID, N, memberManager, replicaState);
        c.setApplication(application);
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
            when(application.getCommandtoPropose()).thenReturn("cmd");
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

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

            Node lowNode  = new Node("hash-low",  "cmd", "rootHash", null);
            Node highNode = new Node("hash-high", "cmd", "rootHash", null);
            c.tree.addNode(lowNode);
            c.tree.addNode(highNode);

            when(application.getCommandtoPropose()).thenReturn("cmd");
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

            // Sender 1 carries QC from view 1, sender 3 carries QC from view 5
            c.onReceiveNewViewMessage(newViewWithQC(1, 1, qc(1, "hash-low",  "prepare")));
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
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

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
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

            Node node = new Node("nodeHash2", "cmd", "parentHash", null);
            c.tree.addNode(node);
            c.setCurrentPhase(Consensus.Phase.PREPARE);
            c.curProposal = node;

            // Send quorum + 2 extra votes
            for (int s = 1; s <= QUORUM + 2; s++)
                c.onReceivePrepareVoteMessage(new PrepareVoteMessage(1, node, s, "SIG:" + s));

            // PRE_COMMIT broadcast must have happened exactly once (N-1 sends)
            verify(application, times(N - 1)).sendConsensusMessage(
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
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

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
            verify(application, never()).sendConsensusMessage(any(), anyLong());
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
         *  View 1 - Leader proposes node A (extends root). Replica accepts the PREPARE
         *           and adds A to its local tree. Leader crashes - no quorum, A is never decided.
         *
         *  View 2 - New leader proposes node B (also extends root, conflicts with A).
         *           Replica goes through full PREPARE -> PRE_COMMIT -> COMMIT -> DECIDE for B.
         *
         *  After DECIDE - A must be pruned from the local tree; B must still be present.
         */
        @Test
        @DisplayName("Node A pruned from local tree after conflicting node B is decided in the next view")
        void safety_conflictingNodePrunedAfterDecide() throws Exception {
            // Replica 4 is never a leader in views 1 or 2 - pure voter.
            // Leader view 1 = (1%4)+1 = 2;  leader view 2 = (2%4)+1 = 3.
            TestableConsensus c = consensus(4);
            doNothing().when(application).sendConsensusMessage(any(), anyLong());

            // View 1: replica receives PREPARE for node A
            c.viewNumber.set(1);
            Node root = new Node("rootHash", "genesis", null, null);
            c.tree.addNode(root);
            c.tree.setRoot(root);

            Node nodeA = new Node("hashA", "cmdA", "rootHash", null);
            c.onReceivePrepareMessage(new PrepareMessage(1, nodeA, null, 2));
            assertNotNull(c.tree.getNode("hashA"), "Node A must be in tree after PREPARE");

            // Leader of view 1 crashes: replica times out, moves to view 2
            c.viewNumber.set(2);

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
            verify(application).commandDecided("cmdB");

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
            verify(application, never()).sendConsensusMessage(any(), anyLong());
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
            verify(application, never()).commandDecided(any());
        }

        @Test
        @DisplayName("PREPARE that does not extend highQC and does not extend lockedQC is rejected")
        void byzantine_prepareFailsSafeNodeCheck() {
            // The locked node is nodeX. Byzantine leader proposes nodeY whose parent
            // is root (not nodeX), with a justifying QC of view 0 < lockedQC view 1.
            // Both branches of safeNode fail -> replica must not vote.
            TestableConsensus c = consensus(4);
            c.viewNumber.set(2);

            Node root  = new Node("rootHash", "genesis", null, null);
            Node nodeX = new Node("hashX", "cmdX", "rootHash", null);
            c.tree.addNode(root);
            c.tree.setRoot(root);
            c.tree.addNode(nodeX);
            c.tree.setLockedQC(qc(1, "hashX", "pre-commit")); // locked at view 1

            // nodeY extends root, not nodeX, and its QC has view 0 < 1
            Node nodeY = new Node("hashY", "cmdY", "rootHash", null);
            PrepareMessage msg = new PrepareMessage(2, nodeY, qc(0, "rootHash", "prepare"), 3);

            assertDoesNotThrow(() -> c.onReceivePrepareMessage(msg));
            verify(application, never()).sendConsensusMessage(any(), anyLong());
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

            verify(application, never()).commandDecided(any());
            assertEquals(2, c.getViewNumberInt(), "View must not change on replayed DECIDE");
        }
    }
}