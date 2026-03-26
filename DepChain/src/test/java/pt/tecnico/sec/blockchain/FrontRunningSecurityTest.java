package pt.tecnico.sec.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.utils.Crypto;

class FrontRunningSecurityTest {

    private BlockchainE2ETestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainE2ETestHarness(3);
    }

    @Test
    @DisplayName("E2E: higher-fee transaction wins ordering regardless of arrival order")
    void higherFeeOrderedFirstEndToEnd() throws Exception {
        long nonce101 = harness.currentNonceFor(101);
        long nonce100 = harness.currentNonceFor(100);
        Command lowFeeFirst = harness.signedTransfer(101, 101, 102, 400, 1, 21_000, nonce101);
        Command highFeeSecond = harness.signedTransfer(100, 100, 102, 400, 5, 21_000, nonce100);

        harness.replica().handleClientTransaction(lowFeeFirst);
        harness.replica().handleClientTransaction(highFeeSecond);

        Block proposed = harness.replica().buildBlock();
        assertNotNull(proposed);
        assertEquals(2, proposed.getTransactions().size());

        long fee0 = proposed.getTransactions().get(0).getGasPrice() * proposed.getTransactions().get(0).getGasLimit();
        long fee1 = proposed.getTransactions().get(1).getGasPrice() * proposed.getTransactions().get(1).getGasLimit();
        assertTrue(fee0 >= fee1);
    }

    @Test
    @DisplayName("E2E: replayed nonce is rejected after first transaction is committed")
    void replayNonceRejectedEndToEnd() throws Exception {
        String sender = Crypto.getClientAddress(100);
        long nonce = harness.currentNonceFor(100);

        Command first = harness.signedTransfer(100, 100, 101, 500, 2, 21_000, nonce);
        harness.replica().handleClientTransaction(first);
        Block block = harness.replica().buildBlock();
        assertNotNull(block);
        harness.replica().onBlockDecided(block.toJson());

        assertEquals(nonce + 1, harness.blockchain().getAccount(sender).getNonce());

        Command replay = harness.signedTransfer(100, 100, 101, 300, 2, 21_000, nonce);
        harness.replica().handleClientTransaction(replay);

        Block staleProposal = harness.replica().buildBlock();
        assertTrue(staleProposal == null || staleProposal.getTransactions().stream()
                .noneMatch(tx -> tx.getFrom().equalsIgnoreCase(sender) && tx.getNonce() == nonce));
    }
}
