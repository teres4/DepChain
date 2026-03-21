package pt.tecnico.sec.blockchain;

import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.account.ContractAccount;

public class TransactionExecutor {

    private static final long BASE_TRANSFER_COST = 21000; // same as ETH
    private static final long BASE_CONTRACT_DEPLOYMENT_COST = 32000; // same as ETH, not including code size cost
    private static final long BASE_CONTRACT_CALL_COST = 50000;
    private static final long GAS_PER_INPUT_BYTE = 16;

    private final Map<String, Account> accounts;
    private final ExecutionContext executionContext;
    private final Transaction transaction;

    public TransactionExecutor(Transaction transaction, Map<String, Account> accounts,
            ExecutionContext executionContext) {
        this.transaction = transaction;
        this.accounts = accounts;
        this.executionContext = executionContext;
    }

    public boolean execute() {
        String senderAddress = transaction.getFrom().toLowerCase();
        Account sender = accounts.get(senderAddress);
        if (sender == null) {
            System.err.println("Sender not found: " + senderAddress);
            return false;
        }

        if (transaction.getNonce() != sender.getNonce()) {
            System.err.printf("Nonce mismatch for %s: expected %d, got %d%n",
                    senderAddress, sender.getNonce(), transaction.getNonce());
            return false;
        }

        // ensure sender can pay for gas
        long maxGasCost = transaction.getGasPrice() * transaction.getGasLimit();
        if (sender.getBalance() < maxGasCost) {
            System.err.printf("Insufficient balance for gas: has %d, needs %d%n", sender.getBalance(), maxGasCost);
            return false;
        }
        sender.deductBalance(maxGasCost);

        boolean success = false;
        try {
            if (transaction.isDepCoinTransfer()) {
                success = executeDepCoinTransfer();
            } else if (transaction.isContractDeployment()) {
                success = executeContractDeployment();
            } else if (transaction.isContractCall()) {
                success = executeContractCall();
            } else {
                throw new IllegalArgumentException("Unknown transaction type");
            }

        } catch (Exception e) {
            System.err.println("Execution error: " + e.getMessage());

            success = false;
        }

        long actualFee = executionContext.getTransactionFee();
        long gasRefund = maxGasCost - actualFee;

        if (gasRefund > 0) {
            sender.addBalance(gasRefund);
        }

        // increment nonce for next transaction
        sender.incrementNonce();
        transaction.setGasUsed(executionContext.getGasUsed());

        if (success) {
            transaction.setState(Transaction.State.EXECUTED);
        } else {
            transaction.setState(Transaction.State.REVERTED);
        }

        return success;
    }

    private boolean executeDepCoinTransfer() {
        executionContext.consumeGas(BASE_TRANSFER_COST);

        String senderAddress = transaction.getFrom().toLowerCase();
        String receiverAddress = transaction.getTo().toLowerCase();
        long amount = transaction.getAmount();

        if (senderAddress.equals(receiverAddress))
            throw new IllegalArgumentException("Sender and receiver cannot be the same");

        if (amount <= 0)
            throw new IllegalArgumentException("Transfer amount must be positive");

        Account sender = accounts.get(senderAddress);
        Account receiver = accounts.get(receiverAddress);

        if (receiver == null)
            throw new IllegalStateException("Receiver account not found: " + receiverAddress);
        if (sender.getBalance() < amount)
            throw new IllegalStateException(
                    "Insufficient balance for transfer: has " + sender.getBalance() + ", needs " + amount);
        sender.deductBalance(amount);
        receiver.addBalance(amount);

        return true;
    }

    // TODO run the bytecode, use EVM
    private boolean executeContractDeployment() {

        byte[] bytecode = transaction.getInput();
        executionContext.consumeGas(BASE_CONTRACT_DEPLOYMENT_COST);
        executionContext.consumeGas(GAS_PER_INPUT_BYTE * bytecode.length);
        // build a SimpleWorld with just the deployer account
        SimpleWorld simpleWorld = new SimpleWorld();
        Address senderAddress = Address.fromHexString(transaction.getFrom());
        Account sender = accounts.get(transaction.getFrom().toLowerCase());

        if (sender == null) {
            throw new IllegalStateException("Sender account not found: " + transaction.getFrom());
        }

        simpleWorld.createAccount(senderAddress, sender.getNonce(), Wei.of(sender.getBalance()));
        // derive the contract address from deployer + nonce
        Address contractAddr = Address.contractAddress(senderAddress, sender.getNonce());

        // run the constructor through the EVM
        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.messageFrameType(MessageFrame.Type.CONTRACT_CREATION);
        executor.sender(senderAddress);
        executor.receiver(contractAddr);
        executor.contract(contractAddr);
        executor.code(Bytes.wrap(bytecode)); // full constructor bytecode
        executor.callData(Bytes.EMPTY);
        executor.gas(transaction.getGasLimit());
        WorldUpdater deploymentUpdater = simpleWorld.updater();
        executor.worldUpdater(deploymentUpdater);
        executor.commitWorldState();
        Bytes constructorOutput = executor.execute();

        MutableAccount besuContract = deploymentUpdater.getAccount(contractAddr);
        if (besuContract == null) {
            besuContract = (MutableAccount) simpleWorld.get(contractAddr);
        }
        if (besuContract == null) {
            // Some callers provide runtime bytecode instead of init bytecode.
            // Retry by wrapping runtime code in a minimal init-code prelude.
            byte[] wrappedInitCode = wrapRuntimeCodeAsInitCode(bytecode);
            if (wrappedInitCode.length != bytecode.length) {
                simpleWorld = new SimpleWorld();
                simpleWorld.createAccount(senderAddress, sender.getNonce(), Wei.of(sender.getBalance()));

                var retryExecutor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
                retryExecutor.messageFrameType(MessageFrame.Type.CONTRACT_CREATION);
                retryExecutor.sender(senderAddress);
                retryExecutor.receiver(contractAddr);
                retryExecutor.contract(contractAddr);
                retryExecutor.code(Bytes.wrap(wrappedInitCode));
                retryExecutor.callData(Bytes.EMPTY);
                retryExecutor.gas(transaction.getGasLimit());
                WorldUpdater retryUpdater = simpleWorld.updater();
                retryExecutor.worldUpdater(retryUpdater);
                retryExecutor.commitWorldState();
                constructorOutput = retryExecutor.execute();

                besuContract = retryUpdater.getAccount(contractAddr);
                if (besuContract == null) {
                    besuContract = (MutableAccount) simpleWorld.get(contractAddr);
                }
            }
        }
        if (besuContract == null) {
            throw new IllegalStateException(
                    "Contract deployment did not create account at " + contractAddr.toHexString()
                            + ". Constructor output: " + constructorOutput.toHexString());
        }

        // Besu stores runtime bytecode in the contract account after constructor
        // execution.
        byte[] runtimeCode = besuContract.getCode().toArray();

        // extract storage written by constructor (e.g. the 100M IST mint)
        Map<String, String> initialStorage = new HashMap<>();
        besuContract.getUpdatedStorage()
                .forEach((k, v) -> initialStorage.put(k.toHexString(), v.toHexString()));

        // create your ContractAccount with runtime code + initial storage
        ContractAccount contractAccount = new ContractAccount(
                contractAddr.toHexString(),
                100000000, // TODO: 100 million
                sender.getNonce(), // nonce at time of deploy
                runtimeCode,
                initialStorage);
        accounts.put(contractAddr.toHexString(), contractAccount);

        return true;
    }

    private boolean executeContractCall() {
        executionContext.consumeGas(BASE_CONTRACT_CALL_COST);
        executionContext.consumeGas(GAS_PER_INPUT_BYTE * transaction.getInput().length);

        String contractAddress = transaction.getTo().toLowerCase();
        Account account = accounts.get(contractAddress);

        if (!(account instanceof ContractAccount)) {
            throw new IllegalStateException("Target is not a contract: " + contractAddress);
        }

        ContractAccount contract = (ContractAccount) account;

        SimpleWorld simpleWorld = new SimpleWorld();

        Address senderAddress = Address.fromHexString(transaction.getFrom());
        Address contractAddr = Address.fromHexString(contractAddress);

        Account sender = accounts.get(transaction.getFrom().toLowerCase());
        simpleWorld.createAccount(senderAddress, sender.getNonce(), Wei.of(sender.getBalance()));
        simpleWorld.createAccount(contractAddr, 0, Wei.of(contract.getBalance()));

        MutableAccount besuContract = (MutableAccount) simpleWorld.get(contractAddr);
        for (Map.Entry<String, String> entry : contract.getStorage().entrySet()) {
            besuContract.setStorageValue(
                    UInt256.fromHexString(entry.getKey()),
                    UInt256.fromHexString(entry.getValue()));
        }

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.code(Bytes.fromHexString(
                org.apache.tuweni.bytes.Bytes.wrap(contract.getRuntimeCode()).toHexString()));
        executor.sender(senderAddress);
        executor.receiver(contractAddr);
        WorldUpdater callUpdater = simpleWorld.updater();
        executor.worldUpdater(callUpdater);
        executor.commitWorldState();
        executor.callData(Bytes.wrap(transaction.getInput()));
        executor.execute();
        Map<String, String> updatedStorage = new HashMap<>(contract.getStorage());
        MutableAccount updatedContract = (MutableAccount) callUpdater.get(contractAddr);
        if (updatedContract != null) {
            updatedContract.getUpdatedStorage()
                    .forEach((k, v) -> updatedStorage.put(k.toHexString(), v.toHexString()));
        }
        contract.updateStorage(updatedStorage);
        accounts.put(contractAddress, contract);

        return true;
    }

    private byte[] wrapRuntimeCodeAsInitCode(byte[] runtimeCode) {
        if (runtimeCode == null || runtimeCode.length == 0) {
            return new byte[0];
        }
        if (runtimeCode.length > 0xFFFF) {
            throw new IllegalArgumentException("Runtime code too large for PUSH2 init wrapper: " + runtimeCode.length);
        }

        int size = runtimeCode.length;
        byte sizeHi = (byte) ((size >> 8) & 0xFF);
        byte sizeLo = (byte) (size & 0xFF);

        // PUSH2 size; PUSH2 0x000d; PUSH0; CODECOPY; PUSH2 size; PUSH0; RETURN
        byte[] prefix = new byte[] {
                0x61, sizeHi, sizeLo,
                0x61, 0x00, 0x0d,
                0x5f,
                0x39,
                0x61, sizeHi, sizeLo,
                0x5f,
                (byte) 0xf3
        };

        byte[] initCode = new byte[prefix.length + runtimeCode.length];
        System.arraycopy(prefix, 0, initCode, 0, prefix.length);
        System.arraycopy(runtimeCode, 0, initCode, prefix.length, runtimeCode.length);
        return initCode;
    }

}
