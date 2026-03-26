package pt.tecnico.sec.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.utils.Crypto;

class ReplicaClientCollusionTest {

    private BlockchainE2ETestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainE2ETestHarness(5);
    }

    @Test
    @DisplayName("E2E collusion: attacker cannot spend victim funds with forged sender")
    void collusionCannotForgeVictimSpend() throws Exception {
        String victim = Crypto.getClientAddress(100);
        long victimBefore = harness.blockchain().getAccount(victim).getBalance();
        long victimNonce = harness.currentNonceFor(100);

        Command forged = harness.signedTransfer(100, 102, 101, 2_000, 3, 21_000, victimNonce);
        harness.replica().handleClientTransaction(forged);

        List<CommandResponse> responses = harness.awaitResponsesFor(100, victimNonce, 1, 1_000);
        assertTrue(responses.stream().anyMatch(r -> !r.isSuccess()));

        Block block = harness.replica().buildBlock();
        assertTrue(block == null || block.getTransactions().isEmpty());
        assertEquals(victimBefore, harness.blockchain().getAccount(victim).getBalance());
    }

    @Test
    @DisplayName("E2E collusion: low-fee malicious tx cannot front-run higher-fee honest tx")
    void collusionCannotBypassFeeOrdering() throws Exception {
        long nonce100 = harness.currentNonceFor(100);
        long nonce102 = harness.currentNonceFor(102);
        Command honestHighFee = harness.signedTransfer(100, 100, 101, 700, 5, 21_000, nonce100);
        Command attackerLowFee = harness.signedTransfer(102, 102, 101, 700, 1, 21_000, nonce102);

        harness.replica().handleClientTransaction(attackerLowFee);
        harness.replica().handleClientTransaction(honestHighFee);

        Block proposed = harness.replica().buildBlock();
        assertNotNull(proposed);
        assertEquals(2, proposed.getTransactions().size());

        long firstFee = proposed.getTransactions().get(0).getGasPrice()
                * proposed.getTransactions().get(0).getGasLimit();
        long secondFee = proposed.getTransactions().get(1).getGasPrice()
                * proposed.getTransactions().get(1).getGasLimit();
        assertTrue(firstFee >= secondFee);
    }

    @Test
    @DisplayName("E2E collusion: stale nonce double-spend attempt is rejected")
    void collusionDoubleSpendStaleNonceRejected() throws Exception {
        String attacker = Crypto.getClientAddress(102);
        long nonce = harness.currentNonceFor(102);

        Command first = harness.signedTransfer(102, 102, 101, 500, 2, 21_000, nonce);
        harness.replica().handleClientTransaction(first);
        Block firstBlock = harness.replica().buildBlock();
        assertNotNull(firstBlock);
        harness.replica().onBlockDecided(firstBlock.toJson());
        assertEquals(1, harness.blockchain().getAccount(attacker).getNonce());

        Command stale = harness.signedTransfer(102, 102, 101, 600, 2, 21_000, nonce);
        harness.replica().handleClientTransaction(stale);

        Block staleProposal = harness.replica().buildBlock();
        assertTrue(staleProposal == null || staleProposal.getTransactions().stream()
                .noneMatch(tx -> tx.getFrom().equalsIgnoreCase(attacker) && tx.getNonce() == nonce));
    }
}
