package pt.tecnico.sec.blockchain;

import java.math.BigInteger;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import org.hyperledger.besu.evm.tracing.StandardJsonTracer;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.OperationTracer;
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
            } else if (transaction.isGetDepCoinBalance()) {
                success = true;
                transaction.setExecutionResult(Long.toString(sender.getBalance()));
            } else if (transaction.isGetIstCoinBalance()) {
                success = executeContractCall(true);
            } else if (transaction.isContractDeployment()) {
                success = executeContractDeployment();
            } else if (transaction.isContractCall()) {
                success = executeContractCall(false);
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

    // TODO: retornamos sempre true
    private boolean executeContractCall(boolean isGetIstBalance) {
        System.out.println("[CALL] Starting contract call from " + transaction.getFrom()
                + " to " + transaction.getTo());
        System.out.println("[CALL] Input length: " + transaction.getInput().length + " bytes");
        System.out.println("[CALL] Calldata (hex): "
                + HexFormat.of().formatHex(transaction.getInput()));

        // validate target is a contract
        String contractAddress = transaction.getTo().toLowerCase();
        Account account = accounts.get(contractAddress);
        if (!(account instanceof ContractAccount)) {
            System.err.println("[CALL] Target is not a contract: " + contractAddress);
            throw new IllegalStateException("Target is not a contract: " + contractAddress);
        }
        ContractAccount contract = (ContractAccount) account;

        // gas: base costs
        long baseCost = BASE_CONTRACT_CALL_COST + GAS_PER_INPUT_BYTE * transaction.getInput().length;
        executionContext.consumeGas(baseCost);
        System.out.println("[CALL] Gas consumed for call base + input: " + baseCost);

        long gasForEVM = transaction.getGasLimit() - baseCost;
        if (gasForEVM <= 0) {
            throw new IllegalStateException("Not enough gas for EVM execution after base costs");
        }

        // setup EVM world
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

        // setup tracer to capture return value
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);

        // setup EVM executor
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
        executor.gas(transaction.getGasLimit());
        executor.tracer(tracer);

        // execute
        System.out.println("[CALL] Executing EVM call...");
        Bytes returnData = executor.execute();
        System.out.println("[CALL] EVM execution complete");

        int number = extractIntegerFromReturnData(byteArrayOutputStream);
        System.out.println(
                "[CALL] Output of 'transfer():' " + Integer.toString(number));

        // TODO: missing evm gas used

        // update storage
        Map<String, String> updatedStorage = new HashMap<>(contract.getStorage());
        MutableAccount updatedContract = (MutableAccount) callUpdater.get(contractAddr);
        if (updatedContract != null) {
            updatedContract.getUpdatedStorage()
                    .forEach((k, v) -> updatedStorage.put(k.toHexString(), v.toHexString()));
        }

        // detect revert by checking if any storage changed
        boolean anyStorageChanged = updatedStorage.entrySet().stream()
                .anyMatch(e -> !e.getValue().equals(
                        contract.getStorage().getOrDefault(e.getKey(), "")));

        if (!anyStorageChanged && !isGetIstBalance) {
            System.err.println("[CALL] EVM call reverted - no storage changes detected");
            return false;
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

        if (isGetIstBalance) {
            BigInteger istBalance = number >= 0 ? BigInteger.valueOf(number) : BigInteger.ZERO;
            System.out.println("[CALL] Parsed IST balance: " + istBalance.toString());
            transaction.setExecutionResult(istBalance.toString());
        }

        System.out.println("[CALL] Contract call completed successfully");
        return true;
    }

    public static int extractIntegerFromReturnData(
            ByteArrayOutputStream byteArrayOutputStream) {
        String[] lines = byteArrayOutputStream.toString().split("\\r?\\n");
        JsonObject jsonObject = JsonParser.parseString(
                lines[lines.length - 1]).getAsJsonObject();

        String memory = jsonObject.get("memory").getAsString();

        JsonArray stack = jsonObject.get("stack").getAsJsonArray();
        int offset = Integer.decode(stack.get(stack.size() - 1).getAsString());
        int size = Integer.decode(stack.get(stack.size() - 2).getAsString());

        String returnData = memory.substring(
                2 + offset * 2,
                2 + offset * 2 + size * 2);
        return Integer.decode("0x" + returnData);
    }

}
