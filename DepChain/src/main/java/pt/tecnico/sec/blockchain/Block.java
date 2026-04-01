package pt.tecnico.sec.blockchain;

import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.account.ContractAccount;
import pt.tecnico.sec.account.EOAccount;

public class Block {
    private final String previousBlockHash;
    private final long blockNumber;
    private final String blockHash;
    private final List<Transaction> transactions;
    private final Map<String, Account> state;

    public Block(String previousBlockHash, long blockNumber,
            List<Transaction> transactions, Map<String, Account> state) {
        this.previousBlockHash = previousBlockHash;
        this.blockNumber = blockNumber;
        this.transactions = sortTransactions(transactions);
        this.state = state;
        this.blockHash = computeHash();
    }

    public Block(String previousBlockHash, String blockHash, long blockNumber,
            List<Transaction> transactions, Map<String, Account> state) {
        this.previousBlockHash = previousBlockHash;
        this.blockHash = blockHash;
        this.blockNumber = blockNumber;
        this.transactions = sortTransactions(transactions);
        this.state = state;
    }

    // For tests that need to construct invalid blocks
    public Block(String previousBlockHash, long blockNumber,
            List<Transaction> transactions, Map<String, Account> state, boolean skipHash) {
        this.previousBlockHash = previousBlockHash;
        this.blockNumber = blockNumber;
        this.transactions = transactions;
        this.state = state;
        this.blockHash = skipHash ? "0000invalidhash" : computeHash();
    }

    private List<Transaction> sortTransactions(List<Transaction> txs) {
        List<Transaction> sorted = new ArrayList<>(txs);

        // Bubble sort for clarity
        for (int i = 0; i < sorted.size() - 1; i++) {
            for (int j = 0; j < sorted.size() - i - 1; j++) {
                if (shouldSwap(sorted.get(j), sorted.get(j + 1))) {
                    Transaction temp = sorted.get(j);
                    sorted.set(j, sorted.get(j + 1));
                    sorted.set(j + 1, temp);
                }
            }
        }

        return sorted;
    }

    private boolean shouldSwap(Transaction a, Transaction b) {
        String senderA = a.getFrom().toLowerCase();
        String senderB = b.getFrom().toLowerCase();

        // Same sender: lower nonce comes first
        if (senderA.equals(senderB)) {
            return a.getNonce() > b.getNonce();
        }

        // Different senders: higher fee comes first
        long feeA = a.getGasPrice() * a.getGasLimit();
        long feeB = b.getGasPrice() * b.getGasLimit();
        if (feeA != feeB) {
            return feeA < feeB;
        }

        // Tie-break by sender address, then nonce, then hash
        int bySender = senderA.compareTo(senderB);
        if (bySender != 0) {
            return bySender > 0;
        }

        int byNonce = Long.compare(a.getNonce(), b.getNonce());
        if (byNonce != 0) {
            return byNonce > 0;
        }

        return a.getHash().compareTo(b.getHash()) > 0;
    }

    public String computeHash() {
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
        JsonDeserializer<Account> accountAdapter = (el, type, ctx) -> {
            if (el.getAsJsonObject().has("runtimecode") || el.getAsJsonObject().has("storage")) {
                return ctx.deserialize(el, ContractAccount.class);
            }
            return ctx.deserialize(el, EOAccount.class);
        };
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(Account.class, accountAdapter)
                .create();
        return gson.fromJson(json, Block.class);
    }

    public void persistToFile(String filePath) throws IOException {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(toJson());
        }
    }

    public String getPreviousBlockHash() {
        return previousBlockHash;
    }

    public long getBlockNumber() {
        return blockNumber;
    }

    public List<Transaction> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }

    public Map<String, Account> getState() {
        return Collections.unmodifiableMap(state);
    }

    public String getBlockHash() {
        return blockHash;
    }
}