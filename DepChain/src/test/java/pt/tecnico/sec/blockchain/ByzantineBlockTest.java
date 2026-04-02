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

    private BlockchainTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainTestHarness(2);
    }

    @Test
    @DisplayName("forged block hash is rejected by replica validation")
    void forgedHashRejected() throws Exception {
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
    @DisplayName("invalidly signed transfer is rejected before block proposal")
    void invalidSignatureRejectedBeforeProposal() throws Exception {
        long nonce = harness.currentNonceFor(100);
        // makes transaction with sender 100 but signed by 101, which should be rejected
        Command forgedSender = harness.signedTransfer(100, 101, 102, 700, 3, 21_000, nonce);
        harness.replica().handleClientTransaction(forgedSender);

        List<CommandResponse> responses = harness.awaitResponsesFor(100, nonce, 1, 1_000);
        assertTrue(responses.stream().anyMatch(r -> !r.isSuccess()));

        Block noBlock = harness.replica().buildBlock();
        assertTrue(noBlock == null || noBlock.getTransactions().isEmpty());
    }

    @Test
    @DisplayName("rejects chain-link tampering with wrong previous hash")
    void wrongPreviousHashRejected() throws Exception {
        long nonce = harness.currentNonceFor(100);
        var tx = harness.signedTransfer(100, 100, 101, 500, 2, 21_000, nonce);
        harness.replica().handleClientTransaction(tx);

        Block valid = harness.replica().buildBlock();
        assertNotNull(valid);

        Block wrongLink = new Block(
                "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                valid.getBlockNumber(),
                new ArrayList<>(valid.getTransactions()),
                valid.getState());

        assertFalse(harness.replica().validateBlock(wrongLink.toJson(), false));
    }

    @Test
    @DisplayName("rejects block with wrong gas order but valid transactions")
    void wrongGasOrderRejected() throws Exception {
        long nonce100 = harness.currentNonceFor(100);
        long nonce101 = harness.currentNonceFor(101);
        Command lowFee = harness.signedTransfer(101, 101, 102, 500, 1000, 22_000, nonce101);
        Command highFee = harness.signedTransfer(100, 100, 102, 100, 1, 21_000, nonce100);

        harness.replica().handleClientTransaction(lowFee);
        harness.replica().handleClientTransaction(highFee);

        Block valid = harness.replica().buildBlock();
        assertNotNull(valid);

        // create a block with the same transactions but in wrong order
        Block wrongOrder = new Block(
                valid.getPreviousBlockHash(),
                valid.getBlockNumber(),
                List.of(valid.getTransactions().get(1), valid.getTransactions().get(0)),
                valid.getState(), false);

        assertFalse(harness.replica().validateBlock(wrongOrder.toJson(), false));
    }
}
