package pt.tecnico.sec.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class Transaction {
    private final String from; // who submitted the transaction
    private final String to; // null for contract deployment
    private final long amount; // DepCoin amount, 0 for contract calls

    // contract call or deployment bytecode, empty for plain DepCoin transfers
    private final byte[] input;

    // price per gas unit
    private final long gasPrice;

    // max gas units willing to spend
    private final long gasLimit;

    private long gasUsed; // set during execution, not part of the transaction hash

    // replay attack protection
    private final long nonce;

    // signature calculated over the fields above
    private final byte[] signature;

    // hash of all fields including signature
    private final String hash;

    private State state; // PENDING, EXECUTED, or REVERTED
    private String executionResult; // execution output for user-facing responses 

    private Type type; // DEPCOIN_TRANSFER, CONTRACT_DEPLOYMENT, CONTRACT_CALL or GET_BALANCE

    enum State {
        PENDING, EXECUTED, REVERTED
    }

    public enum Type {
        DEPCOIN_TRANSFER, CONTRACT_DEPLOYMENT, CONTRACT_CALL, GET_DEPCOIN_BALANCE, GET_ISTCOIN_BALANCE
    }

    public Transaction(Type type, String from, String to, long amount, byte[] input,
            long gasPrice, long gasLimit, long nonce, byte[] signature) {

        // if it calls a contract, then gas price/limit cannot be negative or null
        boolean isGetBalance = type == Type.GET_DEPCOIN_BALANCE;
        if (!isGetBalance && (gasPrice <= 0 || gasLimit <= 0))
                throw new IllegalArgumentException("gasPrice and gasLimit must be positive");

        this.type = type;
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.input = input != null ? input : new byte[0];
        this.gasPrice = gasPrice;
        this.gasLimit = gasLimit;
        this.nonce = nonce;
        this.signature = signature;
        this.hash = computeHash();
        this.state = State.PENDING;
        this.executionResult = null;
    }

    public Transaction(Type type, String from, String to, long amount, 
            long nonce, byte[] signature) {
        this(type, from, to, amount, new byte[0], 0, 0, nonce, signature);
    }

    public byte[] toSignableBytes() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(from.getBytes());
            if (to != null)
                baos.write(to.getBytes());
            ByteBuffer longs = ByteBuffer.allocate(Long.BYTES * 4);
            longs.putLong(amount);
            longs.putLong(gasPrice);
            longs.putLong(gasLimit);
            longs.putLong(nonce);
            baos.write(longs.array());
            baos.write(input);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String computeHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(toSignableBytes());
            if (signature != null)
                digest.update(signature);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // determines how this transaction should be executed
    public boolean isDepCoinTransfer() {
        return input.length == 0 && to != null;
    }

    public boolean isContractDeployment() {
        return to == null;
    }

    public boolean isContractCall() {
        return input.length > 0 && to != null;
    }

    public boolean isGetDepCoinBalance() {
        return type == Type.GET_DEPCOIN_BALANCE;
    }

    public boolean isGetIstCoinBalance() {
        return type == Type.GET_ISTCOIN_BALANCE;
    }

    public boolean isGetBalance() {
        return type == Type.GET_DEPCOIN_BALANCE || type == Type.GET_ISTCOIN_BALANCE;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public long getAmount() {
        return amount;
    }

    public byte[] getInput() {
        return input;
    }

    public long getGasPrice() {
        return gasPrice;
    }

    public long getGasLimit() {
        return gasLimit;
    }

    public long getNonce() {
        return nonce;
    }

    public byte[] getSignature() {
        return signature;
    }

    public String getHash() {
        return hash;
    }

    public long getGasUsed() {
        return gasUsed;
    }

    public void setGasUsed(long gasUsed) {
        this.gasUsed = gasUsed;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(String executionResult) {
        this.executionResult = executionResult;
    }

    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        int inputLen = (input == null) ? 0 : input.length;
        int sigLen = (signature == null) ? 0 : signature.length;
        return "Transaction{" +
                "hash=" + hash +
                ", from=" + from +
                ", to=" + to +
                ", amount=" + amount +
                ", gasPrice=" + gasPrice +
                ", gasLimit=" + gasLimit +
                ", nonce=" + nonce +
                ", inputLen=" + inputLen +
                ", sigLen=" + sigLen +
                ", state=" + state +
                "}";
    }
}