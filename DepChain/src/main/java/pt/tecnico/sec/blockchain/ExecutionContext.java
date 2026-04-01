package pt.tecnico.sec.blockchain;

import pt.tecnico.sec.blockchain.Transaction.Type;

/**
 * Execution context for tracking gas usage during transaction execution.
 * Maintains the accumulated gas used and available gas limit.
 */
public class ExecutionContext {
    private final long gasLimit;
    private final long gasPrice;
    private long gasUsed = 0;

    public ExecutionContext(Type type, long gasLimit, long gasPrice) {
        boolean isBalanceQuery = type == Type.GET_DEPCOIN_BALANCE;
        if (!isBalanceQuery && (gasLimit <= 0 || gasPrice <= 0)) {
            throw new IllegalArgumentException("Gas limit and gas price must be positive");
        }
        this.gasLimit = gasLimit;
        this.gasPrice = gasPrice;
    }

    public void consumeGas(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Gas amount to consume cannot be negative");
        }
        if (gasUsed + amount > gasLimit) {
            throw new IllegalStateException(
                    "Gas limit exceeded: used " + gasUsed + ", trying to consume " + amount + ", limit " + gasLimit);
        }
        gasUsed += amount;
    }

    public long getTransactionFee() {
        // ​min(gas_price​ ​*​ ​gas_limit,​ gas_price​ ​*​gas_used)
        return Math.min(gasPrice * gasLimit, gasPrice * gasUsed);
    }

    public long getGasUsed() {
        return gasUsed;
    }

    public long getGasLimit() {
        return gasLimit;
    }

    public long getGasPrice() {
        return gasPrice;
    }
}
