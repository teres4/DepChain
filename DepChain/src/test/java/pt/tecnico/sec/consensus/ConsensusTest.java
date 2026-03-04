package pt.tecnico.sec.consensus;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.ByzantineConsensus.ByzantineType;
import pt.tecnico.sec.consensus.message.HotStuffMessage;
import pt.tecnico.sec.consensus.message.PrepareMessage;

public class ConsensusTest {

    private MockNetwork network;
    private List<Consensus> replicas;
    private int n = 4; // totalReplicas
    private int f = 1; // byzantine faults tolerated

    @BeforeEach
    public void setup() {
        network = new MockNetwork();
        replicas = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            Consensus replica = new Consensus(i, n, memberManager, mockAuthPerfLink(i));
            replicas.add(replica);
            network.registerReplica(i, replica);
        }
    }

    // ==================== HAPPY PATH ====================

    private AuthPerfLink mockAuthPerfLink(int i) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'mockAuthPerfLink'");
    }

    @Test
    public void testNormalConsensus() throws Exception {
        // All honest replicas should decide on the same value
        startAllReplicas();
        waitForDecision();

        String decided0 = replicas.get(0).getDecidedCommand();
        for (Consensus replica : replicas) {
            assertEquals(decided0, replica.getDecidedCommand());
        }
    }

    // ==================== CRASH FAULTS ====================

    private void startAllReplicas() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'startAllReplicas'");
    }

    @Test
    public void testOneCrashedReplica() throws Exception {
        // Drop all messages from replica 3 (simulates crash)
        network.dropAllFrom(3);

        startAllReplicas();
        waitForDecision();

        // Remaining 3 honest replicas should still decide
        assertEquals(replicas.get(0).getDecidedCommand(),
                     replicas.get(1).getDecidedCommand());
        assertEquals(replicas.get(1).getDecidedCommand(),
                     replicas.get(2).getDecidedCommand());
    }

    @Test
    public void testLeaderCrash() throws Exception {
        // Leader (replica 0) crashes after sending prepare
        network.setInterceptor((to, msg) -> {
            if (msg instanceof PrepareMessage && msg.getSenderId() == 0) {
                network.dropAllFrom(0); // crash after first prepare
            }
            return msg;
        });

        startAllReplicas();
        waitForViewChange();

        // New leader (replica 1) should take over
        assertTrue(replicas.get(1).amLeader(2)); // view 2
    }

    // ==================== BYZANTINE FAULTS ====================

    @Test
    public void testByzantineSilentReplica() throws Exception {
        // Replace replica 3 with a silent byzantine node
        replicas.set(3, new ByzantineConsensus(3, n, memberManager,
                         mockAuthPerfLink(3), ByzantineType.SILENT));
        network.registerReplica(3, replicas.get(3));

        startAllReplicas();
        waitForDecision();

        // Honest replicas should still decide
        assertDecided(replicas.get(0), replicas.get(1), replicas.get(2));
    }

    @Test
    public void testByzantineWrongSignature() throws Exception {
        replicas.set(3, new ByzantineConsensus(3, n, memberManager,
                         mockAuthPerfLink(3), ByzantineType.WRONG_SIGNATURE));
        network.registerReplica(3, replicas.get(3));

        startAllReplicas();
        waitForDecision();

        // Leader should reject the invalid vote and still reach quorum with 3 honest votes
        assertDecided(replicas.get(0), replicas.get(1), replicas.get(2));
    }

    @Test
    public void testByzantineDoubleVote() throws Exception {
        replicas.set(3, new ByzantineConsensus(3, n, memberManager,
                         mockAuthPerfLink(3), ByzantineType.DOUBLE_VOTE));

        startAllReplicas();
        waitForDecision();

        // Safety: all honest replicas must decide the same value
        assertDecided(replicas.get(0), replicas.get(1), replicas.get(2));
    }

    @Test
    public void testByzantineLeaderConflictingProposals() throws Exception {
        // Replace leader (replica 0) with byzantine one that sends conflicting proposals
        replicas.set(0, new ByzantineConsensus(0, n, memberManager,
                         mockAuthPerfLink(0), ByzantineType.INVALID_PROPOSAL));

        startAllReplicas();
        waitForViewChange(); // honest replicas should detect and trigger view change

        // After view change, honest replicas should decide the same value
        waitForDecision();
        assertDecided(replicas.get(1), replicas.get(2), replicas.get(3));
    }

    @Test
    public void testReplayAttack() throws Exception {
        // Capture messages from view 1 and replay them in view 2
        List<HotStuffMessage> capturedMessages = new ArrayList<>();

        network.setInterceptor((to, msg) -> {
            if (msg.getViewNumber() == 1) capturedMessages.add(msg);
            return msg;
        });

        startAllReplicas();
        waitForDecision(); // complete view 1

        // Now replay old messages — should be rejected
        network.setInterceptor((to, msg) -> msg); // reset interceptor
        for (HotStuffMessage old : capturedMessages) {
            network.send(old.getSenderId(), 0, old);
        }

        // Replicas should ignore replayed messages (wrong view number)
        assertEquals(2, replicas.get(0).getViewNumber());
    }

    // ==================== NETWORK FAULTS ====================

    @Test
    public void testMessageDelay() throws Exception {
        // Simulate delayed messages
        network.setInterceptor((to, msg) -> {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            return msg;
        });

        startAllReplicas();
        waitForDecision(10000); // longer timeout for delayed network
        assertDecided(replicas);
    }

    @Test
    public void testPartialMessageLoss() throws Exception {
        // 30% message drop rate across all links
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                network.setDropRate(i, j, 0.3);

        startAllReplicas();
        waitForDecision(15000);
        assertDecided(replicas);
    }

    // ==================== HELPERS ====================

    private void assertDecided(Consensus... replicas) {
        String first = replicas[0].getDecidedCommand();
        assertNotNull(first);
        for (Consensus r : replicas) {
            assertEquals(first, r.getDecidedCommand());
        }
    }

    private void waitForDecision() throws InterruptedException {
        waitForDecision(5000);
    }

    private void waitForDecision(long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (replicas.stream().filter(r -> r.getDecidedCommand() != null).count() >= n - f) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Consensus not reached within timeout");
    }
}