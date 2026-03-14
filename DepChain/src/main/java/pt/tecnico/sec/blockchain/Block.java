package pt.tecnico.sec.blockchain;

import java.io.FileWriter;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import pt.tecnico.sec.account.Account;

public class Block {
    private final String previousBlockHash;
    private final long blockNumber;
    private final List<Transaction> transactions;
    private final Map<String, Account> state;
    private final String blockHash;

    public Block(String previousBlockHash, long blockNumber,
                 List<Transaction> transactions, Map<String, Account> state) {
        this.previousBlockHash = previousBlockHash;
        this.blockNumber = blockNumber;
        // sort by fee descending: fee = gasPrice * gasLimit
        this.transactions = sortByFee(transactions);
        this.state = state;
        this.blockHash = computeHash();
    }


    public Block(String previousBlockHash, String blockHash, long blockNumber,
                 List<Transaction> transactions, Map<String, Account> state) {
        this.previousBlockHash = previousBlockHash;
        this.blockHash = blockHash;
        this.blockNumber = blockNumber;
        this.transactions = sortByFee(transactions);
        this.state = state;
    }

    private List<Transaction> sortByFee(List<Transaction> txs) {
        txs.sort(Comparator.comparingLong(
            (Transaction tx) -> tx.getGasPrice() * tx.getGasLimit()
        ).reversed());
        return txs;
    }

    private String computeHash() {
        try {
            Gson gson = new Gson();
            String content = gson.toJson(previousBlockHash)
                + gson.toJson(blockNumber)
                + gson.toJson(transactions)
                + gson.toJson(state);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content.getBytes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // serialize block to JSON for persistence
    public String toJson() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(this);
    }

    // deserialize block from JSON
    public static Block fromJson(String json) {
        Gson gson = new Gson();
        return gson.fromJson(json, Block.class);
    }

    public void persistToFile(String filePath) throws IOException {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(toJson());
        }    
    }

    public String getPreviousBlockHash() { return previousBlockHash; }
    public long getBlockNumber() { return blockNumber; }
    public List<Transaction> getTransactions() { return Collections.unmodifiableList(transactions); }
    public Map<String, Account> getState() { return Collections.unmodifiableMap(state); }
    public String getBlockHash() { return blockHash; }
}