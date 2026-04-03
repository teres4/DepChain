package pt.tecnico.sec.blockchain;

import java.security.PrivateKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.account.EOAccount;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.client.message.CommandType;
import pt.tecnico.sec.utils.Crypto;

class TransactionExecutorTest {

    private static final String SENDER = "0x1111111111111111111111111111111111111111";
    private static final String RECEIVER = "0x2222222222222222222222222222222222222222";

    private BlockchainTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainTestHarness(2);
    }

    @Test
    @DisplayName("rejects transaction when sender cannot cover max gas")
    void rejectsWhenSenderCannotPayMaxGas() {
        Map<String, Account> accounts = new HashMap<>();
        EOAccount sender = new EOAccount(SENDER, 100, null, 0);
        EOAccount receiver = new EOAccount(RECEIVER, 0, null, 0);
        accounts.put(SENDER, sender);
        accounts.put(RECEIVER, receiver);

        Transaction tx = new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                SENDER,
                RECEIVER,
                1,
                new byte[0],
                10,
                20,
                0,
                new byte[] { 1 });
        ExecutionContext context = new ExecutionContext(tx.getType(), tx.getGasLimit(), tx.getGasPrice());

        boolean success = new TransactionExecutor(tx, accounts, context).execute();

        assertFalse(success);
        assertEquals(100, sender.getBalance());
        assertEquals(0, receiver.getBalance());
        assertEquals(0, sender.getNonce());
        assertEquals(Transaction.State.PENDING, tx.getState());
    }

    @Test
    @DisplayName("executes transfer when gas and amount are affordable")
    void executesWhenGasAndTransferAreAffordable() {
        Map<String, Account> accounts = new HashMap<>();
        EOAccount sender = new EOAccount(SENDER, 100000, null, 0);
        EOAccount receiver = new EOAccount(RECEIVER, 0, null, 0);
        accounts.put(SENDER, sender);
        accounts.put(RECEIVER, receiver);

        Transaction tx;
        tx = new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                SENDER,
                RECEIVER,
                5_000,
                new byte[0],
                1,
                50_000,
                0,
                new byte[] { 1 });
        ExecutionContext context = new ExecutionContext(tx.getType(), tx.getGasLimit(), tx.getGasPrice());

        boolean success = new TransactionExecutor(tx, accounts, context).execute();

        assertTrue(success);
        assertEquals(74_000, sender.getBalance());
        assertEquals(5_000, receiver.getBalance());
        assertEquals(1, sender.getNonce());
        assertEquals(21_000, tx.getGasUsed());
        assertEquals(Transaction.State.EXECUTED, tx.getState());
    }

    @Test
    @DisplayName("rejects replay with stale nonce")
    void rejectsStaleNonceReplay() {
        Map<String, Account> accounts = new HashMap<>();
        EOAccount sender = new EOAccount(SENDER, 100_000, null, 5);
        EOAccount receiver = new EOAccount(RECEIVER, 0, null, 0);
        accounts.put(SENDER, sender);
        accounts.put(RECEIVER, receiver);

        Transaction replay = new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                SENDER,
                RECEIVER,
                10,
                new byte[0],
                1,
                21_000,
                4,
                new byte[] { 1 });
        ExecutionContext context = new ExecutionContext(replay.getType(), replay.getGasLimit(), replay.getGasPrice());

        boolean success = new TransactionExecutor(replay, accounts, context).execute();

        assertFalse(success);
        assertEquals(100_000, sender.getBalance());
        assertEquals(0, receiver.getBalance());
        assertEquals(5, sender.getNonce());
    }

    @Test
    @DisplayName("charges base gas when transfer fails after base cost")
    void chargesGasWhenTransferFailsAfterBaseCost() {
        Map<String, Account> accounts = new HashMap<>();
        EOAccount sender = new EOAccount(SENDER, 100_000, null, 0);
        EOAccount receiver = new EOAccount(RECEIVER, 0, null, 0);
        accounts.put(SENDER, sender);
        accounts.put(RECEIVER, receiver);

        Transaction tx = new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                SENDER,
                RECEIVER,
                200_000,
                new byte[0],
                1,
                50_000,
                0,
                new byte[] { 1 });
        ExecutionContext context = new ExecutionContext(tx.getType(), tx.getGasLimit(), tx.getGasPrice());

        boolean success = new TransactionExecutor(tx, accounts, context).execute();

        assertFalse(success);
        assertEquals(79_000, sender.getBalance());
        assertEquals(0, receiver.getBalance());
        assertEquals(1, sender.getNonce());
        assertEquals(21_000, tx.getGasUsed());
        assertEquals(Transaction.State.REVERTED, tx.getState());
    }

    @Test
    @DisplayName("rejects transactions without gas (zero price or limit)")
    void rejectsTransactionsWithoutGas() {
        assertThrows(IllegalArgumentException.class,
            () -> new Transaction(
                Transaction.Type.CONTRACT_CALL,
                SENDER,
                RECEIVER,
                1,
                new byte[0],
                0,
                0,
                0,
                new byte[0]));

        assertThrows(IllegalArgumentException.class,
            () -> new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                SENDER,
                null,
                0,
                new byte[0],
                1,
                0,
                0,
                new byte[0]));
        
        assertThrows(IllegalArgumentException.class,
            () -> new Transaction(
                Transaction.Type.GET_ISTCOIN_BALANCE,
                SENDER,
                null,
                0,
                new byte[0],
                1,
                0,
                0,
                new byte[0]));
        
        Transaction zeroGasDepBalance = new Transaction(
            Transaction.Type.GET_DEPCOIN_BALANCE,
            SENDER,
            RECEIVER,
            0,
            new byte[0],
            0,
            0,
            0,
            new byte[] { 1 });

        assertDoesNotThrow(() -> new ExecutionContext(
            zeroGasDepBalance.getType(),
            zeroGasDepBalance.getGasLimit(),
            zeroGasDepBalance.getGasPrice()));
    }

    @Test
    @DisplayName("rejects dynamically tampered payloads signed over original bytes")
    void tamperedPayloadRejectedDynamically() throws Exception {
        long nonce = harness.currentNonceFor(100);

        Transaction unsignedOriginal = new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                SENDER,
                RECEIVER,
                10,
                new byte[0],
                1,
                21_000,
                nonce,
                null);

        PrivateKey signerKey;
        signerKey = Crypto.getClientPrivateKey(100);
        String payloadBase64 = Base64.getEncoder().encodeToString(unsignedOriginal.toSignableBytes());
        String signatureBase64 = Crypto.signMessage(payloadBase64, signerKey);
        byte[] validSignature = Base64.getDecoder().decode(signatureBase64);

        long tamperedAmount = 20000;
        Transaction tampered;
        tampered = new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                SENDER,
                RECEIVER,
                tamperedAmount,
                new byte[0],
                1,
                21_000,
                nonce,
                validSignature);

        Command command = new Command(CommandType.TRANSFERDEPCOIN, 100, new Gson().toJson(tampered));
        CommandResponse response = harness.sendAndWaitSingleResponse(command, 100, nonce, 1_000);
        
        assertFalse(response.isSuccess());
        assertTrue(response.getResult().contains("Invalid signature"));
    }

}