package pt.tecnico.sec.blockchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;

class ByzantineBlockTest {

    private BlockchainE2ETestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainE2ETestHarness(2);
    }

    @Test
    @DisplayName("E2E: forged block hash is rejected by replica validation")
    void forgedHashRejectedEndToEnd() throws Exception {
        long nonce = harness.currentNonceFor(100);
        Command tx = harness.signedTransfer(100, 100, 101, 600, 2, 21_000, nonce);
        harness.replica().handleClientTransaction(tx);

        Block valid = harness.replica().buildBlock();
        assertNotNull(valid);

        Block forged = new Block(
                valid.getPreviousBlockHash(),
                "0000deadbeef",
                valid.getBlockNumber(),
                new ArrayList<>(valid.getTransactions()),
                valid.getState());

        assertFalse(harness.replica().validateBlock(forged.toJson(), false));
    }

    @Test
    @DisplayName("E2E: invalidly signed transfer is rejected before block proposal")
    void invalidSignatureRejectedBeforeProposal() throws Exception {
        long nonce = harness.currentNonceFor(100);
        Command forgedSender = harness.signedTransfer(100, 101, 102, 700, 3, 21_000, nonce);
        harness.replica().handleClientTransaction(forgedSender);

        List<CommandResponse> responses = harness.awaitResponsesFor(100, nonce, 1, 1_000);
        assertTrue(responses.stream().anyMatch(r -> !r.isSuccess()));

        Block noBlock = harness.replica().buildBlock();
        assertTrue(noBlock == null || noBlock.getTransactions().isEmpty());
    }
}
