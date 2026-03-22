package pt.tecnico.sec.blockchain;

import java.util.HashMap;
import java.util.HexFormat;
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
        System.out.println("[DEPLOY] Starting contract deployment from " + transaction.getFrom());

        byte[] bytecode = transaction.getInput();
        System.out.println("[DEPLOY] Bytecode length: " + bytecode.length + " bytes");

        executionContext.consumeGas(BASE_CONTRACT_DEPLOYMENT_COST);
        executionContext.consumeGas(GAS_PER_INPUT_BYTE * bytecode.length);
        System.out.println("[DEPLOY] Gas consumed for deployment base + bytecode: " 
            + (BASE_CONTRACT_DEPLOYMENT_COST + GAS_PER_INPUT_BYTE * bytecode.length));

        SimpleWorld simpleWorld = new SimpleWorld();
        Address senderAddress = Address.fromHexString(transaction.getFrom());
        Account sender = accounts.get(transaction.getFrom().toLowerCase());

        if (sender == null) {
            System.err.println("[DEPLOY] Sender account not found: " + transaction.getFrom());
            throw new IllegalStateException("Sender account not found: " + transaction.getFrom());
        }
        System.out.println("[DEPLOY] Sender balance: " + sender.getBalance() 
            + ", nonce: " + sender.getNonce());

        simpleWorld.createAccount(senderAddress, sender.getNonce(), Wei.of(sender.getBalance()));
        System.out.println("[DEPLOY] Using nonce for address derivation: " + sender.getNonce());
        Address contractAddr = Address.contractAddress(senderAddress, sender.getNonce());
        System.out.println("[DEPLOY] Derived contract address: " + contractAddr.toHexString());

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.messageFrameType(MessageFrame.Type.CONTRACT_CREATION);
        executor.sender(senderAddress);
        executor.receiver(contractAddr);
        executor.contract(contractAddr);
        executor.code(Bytes.wrap(bytecode));
        executor.callData(Bytes.EMPTY);
        executor.gas(transaction.getGasLimit());
        WorldUpdater deploymentUpdater = simpleWorld.updater();
        executor.worldUpdater(deploymentUpdater);
        executor.commitWorldState();

        System.out.println("[DEPLOY] Executing constructor...");
        Bytes constructorOutput = executor.execute();
        System.out.println("[DEPLOY] Constructor output length: " + constructorOutput.size() + " bytes");

        MutableAccount besuContract = deploymentUpdater.getAccount(contractAddr);
        if (besuContract == null) {
            besuContract = (MutableAccount) simpleWorld.get(contractAddr);
        }

        if (besuContract == null) {
            System.out.println("[DEPLOY] Contract not found after first attempt, retrying with wrapped init code...");
            byte[] wrappedInitCode = wrapRuntimeCodeAsInitCode(bytecode);
            if (wrappedInitCode.length != bytecode.length) {
                System.out.println("[DEPLOY] Wrapped init code length: " + wrappedInitCode.length + " bytes");
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
                System.out.println("[DEPLOY] Retry constructor output length: " + constructorOutput.size() + " bytes");

                besuContract = retryUpdater.getAccount(contractAddr);
                if (besuContract == null) {
                    besuContract = (MutableAccount) simpleWorld.get(contractAddr);
                }
            }
        }

        if (besuContract == null) {
            System.err.println("[DEPLOY] Failed — no contract account at " + contractAddr.toHexString());
            throw new IllegalStateException(
                    "Contract deployment did not create account at " + contractAddr.toHexString()
                            + ". Constructor output: " + constructorOutput.toHexString());
        }

        byte[] runtimeCode = besuContract.getCode().toArray();
        System.out.println("[DEPLOY] Runtime code length: " + runtimeCode.length + " bytes");

        Map<String, String> initialStorage = new HashMap<>();
        besuContract.getUpdatedStorage()
                .forEach((k, v) -> initialStorage.put(k.toHexString(), v.toHexString()));
        System.out.println("[DEPLOY] Initial storage slots written: " + initialStorage.size());
        initialStorage.forEach((k, v) -> System.out.println("[DEPLOY]   [" + k + "] = " + v));

        ContractAccount contractAccount = new ContractAccount(
                contractAddr.toHexString(),
                100000000,
                sender.getNonce(),
                runtimeCode,
                initialStorage);
        accounts.put(contractAddr.toHexString(), contractAccount);

        System.out.println("[DEPLOY] Contract deployed successfully at " + contractAddr.toHexString());
        return true;
    }

    private boolean executeContractCall() {
        System.out.println("[CALL] Starting contract call from " + transaction.getFrom() 
            + " to " + transaction.getTo());
        System.out.println("[CALL] Input length: " + transaction.getInput().length + " bytes");
        System.out.println("[CALL] Calldata (hex): " 
            + HexFormat.of().formatHex(transaction.getInput()));

        executionContext.consumeGas(BASE_CONTRACT_CALL_COST);
        executionContext.consumeGas(GAS_PER_INPUT_BYTE * transaction.getInput().length);
        System.out.println("[CALL] Gas consumed for call base + input: "
            + (BASE_CONTRACT_CALL_COST + GAS_PER_INPUT_BYTE * transaction.getInput().length));

        String contractAddress = transaction.getTo().toLowerCase();
        Account account = accounts.get(contractAddress);

        if (!(account instanceof ContractAccount)) {
            System.err.println("[CALL] Target is not a contract: " + contractAddress);
            throw new IllegalStateException("Target is not a contract: " + contractAddress);
        }

        ContractAccount contract = (ContractAccount) account;
        System.out.println("[CALL] Contract storage slots before call: " + contract.getStorage().size());
        contract.getStorage().forEach((k, v) -> System.out.println("[CALL]   [" + k + "] = " + v));

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
        System.out.println("[CALL] Loaded " + contract.getStorage().size() + " storage slots into EVM world");

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.code(Bytes.fromHexString(
                org.apache.tuweni.bytes.Bytes.wrap(contract.getRuntimeCode()).toHexString()));
        executor.sender(senderAddress);
        executor.receiver(contractAddr);
        executor.contract(contractAddr);
        WorldUpdater callUpdater = simpleWorld.updater();
        executor.worldUpdater(callUpdater);
        executor.commitWorldState();
        executor.callData(Bytes.wrap(transaction.getInput()));

        System.out.println("[CALL] Executing EVM call...");
        executor.execute();
        System.out.println("[CALL] EVM execution complete");

        Map<String, String> updatedStorage = new HashMap<>(contract.getStorage());
        MutableAccount updatedContract = (MutableAccount) callUpdater.get(contractAddr);
        if (updatedContract != null) {
            updatedContract.getUpdatedStorage()
                    .forEach((k, v) -> updatedStorage.put(k.toHexString(), v.toHexString()));
        }

        System.out.println("[CALL] Storage slots changed by this call:");
        updatedStorage.forEach((k, v) -> {
            String before = contract.getStorage().getOrDefault(k, "(none)");
            if (!v.equals(before)) {
                System.out.println("[CALL]   [" + k + "] " + before + " -> " + v);
            }
        });
        System.out.println("[CALL] Total storage slots after call: " + updatedStorage.size());

        contract.updateStorage(updatedStorage);
        accounts.put(contractAddress, contract);

        System.out.println("[CALL] Contract call completed successfully");
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
