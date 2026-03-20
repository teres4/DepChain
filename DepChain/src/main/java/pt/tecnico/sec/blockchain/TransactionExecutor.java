package pt.tecnico.sec.blockchain;

import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;

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

        if (success) {
            long actualFee = executionContext.getTransactionFee();
            long gasRefund = maxGasCost - actualFee;

            if (gasRefund > 0) {
                sender.addBalance(gasRefund);
            }
        }

        // increment nonce for next transaction
        sender.incrementNonce();
        
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

        return true;
    }
    
    private boolean executeContractCall() {
        executionContext.consumeGas(BASE_CONTRACT_CALL_COST);
        executionContext.consumeGas(GAS_PER_INPUT_BYTE * transaction.getInput().length);

        String contractAddress = transaction.getTo();
        Account account = accounts.get(contractAddress);

        if (!(account instanceof ContractAccount)) {
            throw new IllegalStateException("Target is not a contract: " + contractAddress);
        }

        ContractAccount contract = (ContractAccount) account;

        SimpleWorld simpleWorld = new SimpleWorld();

        Address senderAddress = Address.fromHexString(transaction.getFrom());
        Address contractAddr = Address.fromHexString(contractAddress);

        Account sender = accounts.get(transaction.getFrom());
        simpleWorld.createAccount(senderAddress, sender.getNonce(), Wei.of(sender.getBalance()));
        simpleWorld.createAccount(contractAddr, 0, Wei.of(contract.getBalance()));

        // 
        MutableAccount besuContract = (MutableAccount) simpleWorld.get(contractAddr);
        for (Map.Entry<String, String> entry : contract.getStorage().entrySet()) {
            besuContract.setStorageValue(
                UInt256.fromHexString(entry.getKey()),
                UInt256.fromHexString(entry.getValue())
            );
        }

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.code(Bytes.fromHexString(
            org.apache.tuweni.bytes.Bytes.wrap(contract.getCode()).toHexString()
        ));
        executor.sender(senderAddress);
        executor.receiver(contractAddr);
        executor.worldUpdater(simpleWorld.updater());
        executor.commitWorldState();
        executor.callData(Bytes.wrap(transaction.getInput()));
        executor.execute();

        Map<String, String> updatedStorage = new HashMap<>();
        for (Map.Entry<String, String> entry : contract.getStorage().entrySet()) {
            UInt256 slot = UInt256.fromHexString(entry.getKey());
            UInt256 updatedValue = simpleWorld.get(contractAddr).getStorageValue(slot);
            updatedStorage.put(entry.getKey(), updatedValue.toHexString());
        }
        contract.updateStorage(updatedStorage);
        accounts.put(contractAddress, contract);

        return true;
    }

}
