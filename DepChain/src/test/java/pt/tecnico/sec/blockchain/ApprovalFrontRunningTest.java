package pt.tecnico.sec.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.utils.Numeric;

import com.google.gson.Gson;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandType;
import pt.tecnico.sec.utils.Crypto;

class ApprovalFrontRunningTest {

    private static final long GAS_LIMIT = 200_000;

    private BlockchainTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new BlockchainTestHarness(1);
    }

    @Test
    @DisplayName("approval front-running causes stale safeApprove to revert")
    void approvalFrontRunningRejected() throws Exception {
        String contract = findContractAddress();
        String owner = Crypto.getClientAddress(100);
        String spender = Crypto.getClientAddress(101);
        String recipient = Crypto.getClientAddress(102);

        long ownerNonce = harness.currentNonceFor(100);
        // owner approves spender for 1000 tokens with safeApprove
        Command approve = signedContractCall(
                100,
                100,
                contract,
                safeApproveInput(spender, 0, 1000),
                1,
                GAS_LIMIT,
                ownerNonce,
                CommandType.APPROVEISTCOIN);

        harness.replica().handleClientTransaction(approve);
        Block block1 = harness.replica().buildBlock();
        assertNotNull(block1);
        harness.replica().onBlockDecided(block1.toJson());

        Block decided1 = harness.blockchain().getBlock(harness.blockchain().getChainHeight() - 1);
        assertEquals(Transaction.State.EXECUTED, decided1.getTransactions().get(0).getState());

        long spenderNonce = harness.currentNonceFor(101);
        long ownerNonce2 = harness.currentNonceFor(100);

        // owner tries to update allowance to 500 with stale safeApprove, which should revert due to front-running
        Command staleApprove = signedContractCall(
                100,
                100,
                contract,
                safeApproveInput(spender, 1000, 500),
                1,
                GAS_LIMIT,
                ownerNonce2,
                CommandType.APPROVEISTCOIN);

        // spender front-runs with transferFrom of 1000 tokens, which should succeed
        Command transferFrom = signedContractCall(
                101,
                101,
                contract,
                transferFromInput(owner, recipient, 1000),
                5,
                GAS_LIMIT,
                spenderNonce,
                CommandType.TRANSFERFROMISTCOIN);

        harness.replica().handleClientTransaction(staleApprove);
        harness.replica().handleClientTransaction(transferFrom);

        Block block2 = harness.replica().buildBlock();
        assertNotNull(block2);
        assertEquals(2, block2.getTransactions().size());
        assertEquals(spender.toLowerCase(), block2.getTransactions().get(0).getFrom().toLowerCase());

        harness.replica().onBlockDecided(block2.toJson());
        Block decided2 = harness.blockchain().getBlock(harness.blockchain().getChainHeight() - 1);

        Transaction first = decided2.getTransactions().get(0);
        Transaction second = decided2.getTransactions().get(1);

        // transferFrom should execute successfully, 
        // but the stale safeApprove should revert due to front-running
        assertEquals(Transaction.State.EXECUTED, first.getState());
        assertEquals(Transaction.State.REVERTED, second.getState());
    }

    @Test
    @DisplayName("safeApprove succeeds with correct expected allowance after spend")
    void safeApproveSucceedsAfterSpend() throws Exception {
        String contract = findContractAddress();
        String owner = Crypto.getClientAddress(100);
        String spender = Crypto.getClientAddress(101);
        String recipient = Crypto.getClientAddress(102);

        long ownerNonce = harness.currentNonceFor(100);
        Command approve = signedContractCall(
                100,
                100,
                contract,
                safeApproveInput(spender, 0, 1000),
                1,
                GAS_LIMIT,
                ownerNonce,
                CommandType.APPROVEISTCOIN);

        harness.replica().handleClientTransaction(approve);
        Block block1 = harness.replica().buildBlock();
        assertNotNull(block1);
        harness.replica().onBlockDecided(block1.toJson());

        long spenderNonce = harness.currentNonceFor(101);
        Command transferFrom = signedContractCall(
                101,
                101,
                contract,
                transferFromInput(owner, recipient, 1000),
                5,
                GAS_LIMIT,
                spenderNonce,
                CommandType.TRANSFERFROMISTCOIN);

        harness.replica().handleClientTransaction(transferFrom);
        Block block2 = harness.replica().buildBlock();
        assertNotNull(block2);
        harness.replica().onBlockDecided(block2.toJson());

        long ownerNonce2 = harness.currentNonceFor(100);
        Command safeApprove = signedContractCall(
                100,
                100,
                contract,
                safeApproveInput(spender, 0, 500),
                1,
                GAS_LIMIT,
                ownerNonce2,
                CommandType.APPROVEISTCOIN);

        harness.replica().handleClientTransaction(safeApprove);
        Block block3 = harness.replica().buildBlock();
        assertNotNull(block3);
        harness.replica().onBlockDecided(block3.toJson());

        Block decided3 = harness.blockchain().getBlock(harness.blockchain().getChainHeight() - 1);
        assertEquals(Transaction.State.EXECUTED, decided3.getTransactions().get(0).getState());
    }

    @Test
    @DisplayName("safeApprove blocks front-running that would allow extra spend")
    void safeApproveBlocksFrontRunning() throws Exception {
        String contract = findContractAddress();
        String owner = Crypto.getClientAddress(100);
        String spender = Crypto.getClientAddress(101);
        String recipient = Crypto.getClientAddress(102);

        long ownerNonce = harness.currentNonceFor(100);
        Command approve100 = signedContractCall(
                100,
                100,
                contract,
                approveInput(spender, 100),
                1,
                GAS_LIMIT,
                ownerNonce,
                CommandType.APPROVEISTCOIN);

        harness.replica().handleClientTransaction(approve100);
        Block block1 = harness.replica().buildBlock();
        assertNotNull(block1);
        harness.replica().onBlockDecided(block1.toJson());

        long spenderNonce = harness.currentNonceFor(101);
        long ownerNonce2 = harness.currentNonceFor(100);

        Command safeApprove50 = signedContractCall(
                100,
                100,
                contract,
                safeApproveInput(spender, 100, 50),
                1,
                GAS_LIMIT,
                ownerNonce2,
                CommandType.APPROVEISTCOIN);

        Command transfer100 = signedContractCall(
                101,
                101,
                contract,
                transferFromInput(owner, recipient, 100),
                10,
                GAS_LIMIT,
                spenderNonce,
                CommandType.TRANSFERFROMISTCOIN);

        harness.replica().handleClientTransaction(safeApprove50);
        harness.replica().handleClientTransaction(transfer100);

        Block block2 = harness.replica().buildBlock();
        assertNotNull(block2);
        assertEquals(2, block2.getTransactions().size());
        assertEquals(spender.toLowerCase(), block2.getTransactions().get(0).getFrom().toLowerCase());

        harness.replica().onBlockDecided(block2.toJson());
        Block decided2 = harness.blockchain().getBlock(harness.blockchain().getChainHeight() - 1);

        assertEquals(Transaction.State.EXECUTED, decided2.getTransactions().get(0).getState());
        assertEquals(Transaction.State.REVERTED, decided2.getTransactions().get(1).getState());

        long spenderNonce2 = harness.currentNonceFor(101);
        Command transfer50 = signedContractCall(
                101,
                101,
                contract,
                transferFromInput(owner, recipient, 50),
                1,
                GAS_LIMIT,
                spenderNonce2,
                CommandType.TRANSFERFROMISTCOIN);

        harness.replica().handleClientTransaction(transfer50);
        Block block3 = harness.replica().buildBlock();
        assertNotNull(block3);
        harness.replica().onBlockDecided(block3.toJson());

        Block decided3 = harness.blockchain().getBlock(harness.blockchain().getChainHeight() - 1);
                assertEquals(Transaction.State.REVERTED, decided3.getTransactions().get(0).getState());
    }

    private Command signedContractCall(long claimedSenderId,
            long signerId,
            String contractAddress,
            byte[] input,
            long gasPrice,
            long gasLimit,
            long nonce,
            CommandType type) throws Exception {
        String from = Crypto.getClientAddress(claimedSenderId);

        Transaction unsigned = new Transaction(
                Transaction.Type.CONTRACT_CALL,
                from,
                contractAddress,
                0,
                input,
                gasPrice,
                gasLimit,
                nonce,
                null);

        String signatureBase64 = Crypto.signMessage(
                java.util.Base64.getEncoder().encodeToString(unsigned.toSignableBytes()),
                Crypto.getClientPrivateKey(signerId));

        Transaction signed = new Transaction(
                Transaction.Type.CONTRACT_CALL,
                from,
                contractAddress,
                0,
                input,
                gasPrice,
                gasLimit,
                nonce,
                java.util.Base64.getDecoder().decode(signatureBase64));

        return new Command(type, claimedSenderId, new Gson().toJson(signed));
    }

    private byte[] safeApproveInput(String spenderAddress, long expected, long newAllowance) {
        Function function = new Function(
                "safeApprove",
                Arrays.asList(
                        new Address(spenderAddress),
                        new Uint256(BigInteger.valueOf(expected)),
                        new Uint256(BigInteger.valueOf(newAllowance))),
                Collections.emptyList());
        return Numeric.hexStringToByteArray(FunctionEncoder.encode(function));
    }

    private byte[] approveInput(String spenderAddress, long amount) {
        Function function = new Function(
                "approve",
                Arrays.asList(
                        new Address(spenderAddress),
                        new Uint256(BigInteger.valueOf(amount))),
                Collections.emptyList());
        return Numeric.hexStringToByteArray(FunctionEncoder.encode(function));
    }

    private byte[] transferFromInput(String ownerAddress, String recipientAddress, long amount) {
        Function function = new Function(
                "transferFrom",
                Arrays.asList(
                        new Address(ownerAddress),
                        new Address(recipientAddress),
                        new Uint256(BigInteger.valueOf(amount))),
                Collections.emptyList());
        return Numeric.hexStringToByteArray(FunctionEncoder.encode(function));
    }

    private String findContractAddress() {
        for (Account account : harness.blockchain().getAccounts().values()) {
            if (account.isContract()) {
                return account.getAddress();
            }
        }
        throw new IllegalStateException("No contract account found in genesis state");
    }
}
