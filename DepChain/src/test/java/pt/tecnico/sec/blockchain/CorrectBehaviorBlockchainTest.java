package pt.tecnico.sec.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.utils.Crypto;

class CorrectBehaviorBlockchainTest {

    private BlockchainE2ETestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainE2ETestHarness(1);
    }

    @Test
    @DisplayName("E2E: signed transfer is accepted, included in block, and applied to state")
    void transferLifecycleEndToEnd() throws Exception {
        String sender = Crypto.getClientAddress(100);
        String recipient = Crypto.getClientAddress(101);
        long senderNonce = harness.currentNonceFor(100);

        long senderBefore = harness.blockchain().getAccount(sender).getBalance();
        long recipientBefore = harness.blockchain().getAccount(recipient).getBalance();

        Command cmd = harness.signedTransfer(100, 100, 101, 1_000, 2, 21_000, senderNonce);
        harness.replica().handleClientTransaction(cmd);

        Block proposed = harness.replica().buildBlock();
        assertNotNull(proposed);
        assertEquals(1, proposed.getTransactions().size());

        harness.replica().onBlockDecided(proposed.toJson());

        List<CommandResponse> responses = harness.awaitResponsesFor(100, senderNonce, 2, 1_500);
        assertTrue(responses.stream().anyMatch(CommandResponse::isSuccess));
        assertTrue(responses.stream().anyMatch(r -> r.getResult().contains("included in block")));

        Account senderAfter = harness.blockchain().getAccount(sender);
        Account recipientAfter = harness.blockchain().getAccount(recipient);
        assertTrue(senderAfter.getBalance() < senderBefore);
        assertTrue(recipientAfter.getBalance() > recipientBefore);
        assertEquals(senderNonce + 1, senderAfter.getNonce());
    }

    @Test
    @DisplayName("E2E: multi-sender transactions are ordered by fee and both execute")
    void multiSenderFeeOrderingEndToEnd() throws Exception {
        String sender100 = Crypto.getClientAddress(100);
        String sender101 = Crypto.getClientAddress(101);
        long sender100Nonce = harness.currentNonceFor(100);
        long sender101Nonce = harness.currentNonceFor(101);

        Command lowerFee = harness.signedTransfer(101, 101, 102, 500, 1, 21_000, sender101Nonce);
        Command higherFee = harness.signedTransfer(100, 100, 102, 700, 4, 21_000, sender100Nonce);

        harness.replica().handleClientTransaction(lowerFee);
        harness.replica().handleClientTransaction(higherFee);

        Block proposed = harness.replica().buildBlock();
        assertNotNull(proposed);
        assertEquals(2, proposed.getTransactions().size());

        long firstFee = proposed.getTransactions().get(0).getGasPrice()
                * proposed.getTransactions().get(0).getGasLimit();
        long secondFee = proposed.getTransactions().get(1).getGasPrice()
                * proposed.getTransactions().get(1).getGasLimit();
        assertTrue(firstFee >= secondFee);

        harness.replica().onBlockDecided(proposed.toJson());

        assertEquals(sender100Nonce + 1, harness.blockchain().getAccount(sender100).getNonce());
        assertEquals(sender101Nonce + 1, harness.blockchain().getAccount(sender101).getNonce());
    }
}
