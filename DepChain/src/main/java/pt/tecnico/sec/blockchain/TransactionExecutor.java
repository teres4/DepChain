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
        try {
            String senderAddress = transaction.getFrom().toLowerCase();
            Account sender = accounts.get(senderAddress);
            if (sender == null) {
                throw new IllegalStateException("Sender account not found: " + senderAddress);
            }
            
            // validate nonce
            if (transaction.getNonce() != sender.getNonce()) {
                throw new IllegalArgumentException(
                    "Invalid nonce: expected " + sender.getNonce() + ", got " + transaction.getNonce());
            }
            
            // ensure sender can pay for gas
            long maxGasCost = transaction.getGasPrice() * transaction.getGasLimit();
            if (sender.getBalance() < maxGasCost) {
                throw new IllegalStateException(
                    "Insufficient balance for gas: has " + sender.getBalance() + ", needs " + maxGasCost);
            }
            
            // deduct max gas cost 
            sender.deductBalance(maxGasCost);
            
            boolean success;
            if (transaction.isDepCoinTransfer()) {
                success = executeDepCoinTransfer();
            } else if (transaction.isContractDeployment()) {
                success = executeContractDeployment();
            } else if (transaction.isContractCall()) {
                success = executeContractCall();
            } else {
                throw new IllegalArgumentException("Unknown transaction type");
            }
            
            // calculate refund and refund unused gas
            long gasRefund = transaction.getGasPrice() * (transaction.getGasLimit() - executionContext.getGasUsed());
            sender.addBalance(gasRefund);
            
            // increment nonce for next transaction
            sender.incrementNonce();
            
            return success;
            
        } catch (Exception e) {
            // In case of failure, the full gas cost is not refunded
            // The transaction fee is paid, but no state changes occur
            return false;
        }
    }
    
    private boolean executeDepCoinTransfer() {
        String receiverAddress = transaction.getTo().toLowerCase();
        long amount = transaction.getAmount();
        
        // Validate receiver exists (or create account)
        Account receiver = accounts.get(receiverAddress);
        if (receiver == null) {
            throw new IllegalStateException("Receiver account not found: " + receiverAddress);
        }
        
        Account sender = accounts.get(transaction.getFrom().toLowerCase());
        if (sender.getBalance() < amount) {
            throw new IllegalStateException(
                "Insufficient balance for transfer: has " + sender.getBalance() + 
                ", needs " + amount);
        }
        
        sender.deductBalance(amount);
        receiver.addBalance(amount);
        
        return true;
    }
    
    // TODO
    private boolean executeContractDeployment() {
        byte[] bytecode = transaction.getInput();
        
        return true;
    }
    
    // TODO
    private boolean executeContractCall() {
        return true;
    }

}
