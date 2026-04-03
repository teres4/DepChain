package pt.tecnico.sec.blockchain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;

class MaliciousReplicaBehaviorTest {

    private BlockchainTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainTestHarness(4);
    }

    @Test
    @DisplayName("invalid signature is rejected before entering mempool")
    void invalidSignatureRejectedAtIngress() throws Exception {
        long nonce = harness.currentNonceFor(100);
        Command forged = harness.signedTransfer(100, 101, 102, 800, 2, 21_000, nonce);
        harness.replica().handleClientTransaction(forged);

        List<CommandResponse> responses = harness.awaitResponsesFor(100, nonce, 1, 1_000);
        assertTrue(responses.stream().anyMatch(r -> !r.isSuccess()));

        Block block = harness.replica().buildBlock();
        assertTrue(block == null || block.getTransactions().isEmpty());
    }

    @Test
    @DisplayName("malformed request fails while honest request still executes")
    void malformedAndHonestRequestsCoexist() throws Exception {
        long nonce100 = harness.currentNonceFor(100);
        long nonce101 = harness.currentNonceFor(101);
        Command malformed = harness.signedTransfer(100, 101, 102, 500, 2, 21_000, nonce100);
        Command honest = harness.signedTransfer(101, 101, 102, 500, 3, 21_000, nonce101);

        harness.replica().handleClientTransaction(malformed);
        harness.replica().handleClientTransaction(honest);

        List<CommandResponse> malformedResp = harness.awaitResponsesFor(100, nonce100, 1, 1_000);
        assertTrue(malformedResp.stream().anyMatch(r -> !r.isSuccess()));

        Block proposed = harness.replica().buildBlock();
        assertNotNull(proposed);
        assertEquals(1, proposed.getTransactions().size());

        harness.replica().onBlockDecided(proposed.toJson());
        List<CommandResponse> honestResp = harness.awaitResponsesFor(101, nonce101, 2, 1_500);
        assertTrue(honestResp.stream().anyMatch(r -> r.isSuccess()));
    }
}
