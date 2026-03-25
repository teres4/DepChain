package pt.tecnico.sec.blockchain;

import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
        this.transactions = new ArrayList<>(transactions);
        this.state = state;
        this.blockHash = computeHash();
    }

    public Block(String previousBlockHash, String blockHash, long blockNumber,
                 List<Transaction> transactions, Map<String, Account> state) {
        this.previousBlockHash = previousBlockHash;
        this.blockHash = blockHash;
        this.blockNumber = blockNumber;
        this.transactions = new ArrayList<>(transactions);
        this.state = state;
    }

    // TODO: por enquanto vou deixar aqui no caso de nao ser preciso pre-executar as txs para saber o actual gasUsed
    private List<Transaction> sortByFee(List<Transaction> txs) {
        txs.sort(Comparator.comparingLong(
            (Transaction tx) -> tx.getGasPrice() * tx.getGasLimit()
        ).reversed());
        return txs;
    }

    public List<Transaction> sortByActualFee(List<Transaction> txs) {
        Map<String, Account> accountsCopy = deepCopyAccounts(state);
        Map<String, Long> actualFees = new HashMap<>();
        
        for (Transaction tx : txs) {
            ExecutionContext dryRunContext = new ExecutionContext(tx.getGasLimit(), tx.getGasPrice());
            TransactionExecutor dryRun = new TransactionExecutor(tx, accountsCopy, dryRunContext);
            dryRun.execute();
            long actualFee = tx.getGasPrice() * dryRunContext.getGasUsed();
            actualFees.put(tx.getHash(), actualFee);
        }
        
        txs.sort(Comparator.comparingLong(
            (Transaction tx) -> actualFees.getOrDefault(tx.getHash(), 0L)
        ).reversed());
        
        return txs;
    }

    private Map<String, Account> deepCopyAccounts(Map<String, Account> original) {
        Map<String, Account> copy = new HashMap<>();
        for (Map.Entry<String, Account> entry : original.entrySet()) {
            Account acc = entry.getValue();
            if (acc instanceof ContractAccount ca) {
                copy.put(entry.getKey(), new ContractAccount(
                    ca.getAddress(), ca.getBalance(), ca.getRuntimeCode(),
                    new HashMap<>(ca.getStorage()), ca.getNonce()));
            } else if (acc instanceof EOAccount eo) {
                copy.put(entry.getKey(), new EOAccount(
                    eo.getAddress(), eo.getBalance(),
                    eo.getPublicKey(), eo.getNonce()));
            }
        }
        return copy;
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