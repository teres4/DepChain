package pt.tecnico.sec.blockchain;

import java.util.Map;

import pt.tecnico.sec.account.Account;

public class TransactionExecutor {

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

        boolean success = false;
        try {
            // deduct max gas cost 
            sender.deductBalance(maxGasCost);

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
        String senderAddress = transaction.getFrom().toLowerCase();
        Account sender = accounts.get(senderAddress);

        String receiverAddress = transaction.getTo().toLowerCase();
        long amount = transaction.getAmount();
        
        // Validate receiver exists (or create account)
        Account receiver = accounts.get(receiverAddress);
        if (receiver == null) {
            throw new IllegalStateException("Receiver account not found: " + receiverAddress);
        }

        if (senderAddress.equals(receiverAddress)) {
            throw new IllegalArgumentException("Sender and receiver cannot be the same");
        }

        if (amount < 0) {
            throw new IllegalArgumentException("Transfer amount cannot be negative");
        }
        
        if (sender.getBalance() < amount) {
            throw new IllegalStateException(
                "Insufficient balance for transfer: has " + sender.getBalance() + ", needs " + amount);
        }
        
        sender.deductBalance(amount);
        receiver.addBalance(amount);
        
        return true;
    }
    
    // TODO run the bytecode, use EVM
    private boolean executeContractDeployment() {
        byte[] bytecode = transaction.getInput();
        
        return true;
    }
    
    // TODO call deployed contract
    private boolean executeContractCall() {
        return true;
    }

}
