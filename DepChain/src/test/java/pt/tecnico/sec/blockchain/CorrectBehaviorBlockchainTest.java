package pt.tecnico.sec.blockchain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.utils.Crypto;

class CorrectBehaviorBlockchainTest {

    private BlockchainTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainTestHarness(1);
    }

    @Test
    @DisplayName("signed transfer is accepted, included in block, and applied to state")
    void transferLifecycle() throws Exception {
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
    @DisplayName("multi-sender transactions are ordered by fee and both execute")
    void multiSenderFeeOrdering() throws Exception {
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

    // client A sends transfer with nonce N and low fee and nonce N + 1 with highest fee, client B sends transfer with same nonce N and higher fee, 
    // client A's transaction with N+1 is executed after A's transaction with N and B's transaction with N
    @Test
    @DisplayName("multi-sender transactions with same fee are included and both execute")
    void multiSenderSameFeeOrdering() throws Exception {
        String sender100 = Crypto.getClientAddress(100);
        String sender101 = Crypto.getClientAddress(101);
        long sender100Nonce = harness.currentNonceFor(100);
        long sender101Nonce = harness.currentNonceFor(101);

        Command firstReq = harness.signedTransfer(100, 100, 102, 500, 2, 21_000, sender100Nonce);
        Command secondReq = harness.signedTransfer(101, 101, 102, 700, 3, 21_000, sender101Nonce);
        Command thirdReq = harness.signedTransfer(100, 100, 102, 700, 5, 21_000, sender100Nonce + 1);

        harness.replica().handleClientTransaction(firstReq);
        harness.replica().handleClientTransaction(secondReq);
        harness.replica().handleClientTransaction(thirdReq);

        Block proposed = harness.replica().buildBlock();
        assertNotNull(proposed);
        assertEquals(3, proposed.getTransactions().size());

        assertTrue(proposed.getTransactions().stream().anyMatch(tx -> tx.getFrom().equalsIgnoreCase(sender100)));
        assertTrue(proposed.getTransactions().stream().anyMatch(tx -> tx.getFrom().equalsIgnoreCase(sender101)));

        long fee0 = proposed.getTransactions().get(0).getGasPrice() * proposed.getTransactions().get(0).getGasLimit(); // should be second req
        long fee1 = proposed.getTransactions().get(1).getGasPrice() * proposed.getTransactions().get(1).getGasLimit(); // should be first req
        long fee2 = proposed.getTransactions().get(2).getGasPrice() * proposed.getTransactions().get(2).getGasLimit(); // should be third req

        // order should be second req (fee0), first req (fee1), third req (fee2)
        assertTrue(fee0 > fee1);
        assertTrue(fee2 > fee0);
        assertTrue(fee2 > fee1);
        assertEquals(sender101Nonce, proposed.getTransactions().get(0).getNonce());
        assertEquals(sender100Nonce, proposed.getTransactions().get(1).getNonce());
        assertEquals(sender100Nonce + 1, proposed.getTransactions().get(2).getNonce());
        
        harness.replica().onBlockDecided(proposed.toJson());

        assertEquals(sender100Nonce + 2, harness.blockchain().getAccount(sender100).getNonce());
        assertEquals(sender101Nonce + 1, harness.blockchain().getAccount(sender101).getNonce());
    }
}
