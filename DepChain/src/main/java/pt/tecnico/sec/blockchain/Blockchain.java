package pt.tecnico.sec.blockchain;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.account.ContractAccount;
import pt.tecnico.sec.account.EOAccount;
import pt.tecnico.sec.utils.Crypto;

public class Blockchain {
    public static final long BLOCK_GAS_LIMIT = 50_000_000L;

    private final List<Block> chain = new ArrayList<>();

    // all accounts and their current balances / nonces / code
    private final Map<String, Account> accounts = new HashMap<>();

    private final File storageDir;
    private static final String GENESIS_PATH = "config/block/genesis.json";

    public Blockchain(long memberId) {
        String blockStorageDir = "blocks/member_" + memberId;
        storageDir = new File(blockStorageDir);
        if (storageDir.exists()) {
            File[] files = storageDir.listFiles();
            if (files != null) {
                for (File file : files)
                    file.delete();
            }
        }
        storageDir.mkdirs();
        loadGenesis();
    }

    private void loadGenesis() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try {
            JsonNode genesisNode = mapper.readTree(new File(GENESIS_PATH));
            String blockHash = genesisNode.get("block_hash").asText();
            String previousHash = genesisNode.get("previous_block_hash").asText(null);

            // load transactions from genesis file
            List<Transaction> transactions = new ArrayList<>();
            for (JsonNode txNode : genesisNode.get("transactions")) {
                String from = getText(txNode, "from");
                String to = getText(txNode, "to");

                long amount = getLong(txNode, "amount");
                long gasPrice = getLong(txNode, "gas_price");
                long gasLimit = getLong(txNode, "gas_limit");
                long nonce = getLong(txNode, "nonce");

                byte[] input = decodeHex(getText(txNode, "input"));

                transactions.add(new Transaction(Transaction.Type.CONTRACT_DEPLOYMENT, from, to, amount, input, gasPrice, gasLimit, nonce, null));
            }

            // load accounts from genesis file
            JsonNode stateNode = genesisNode.get("state");
            Iterator<String> addresses = stateNode.fieldNames();
            while (addresses.hasNext()) {
                String address = addresses.next();
                JsonNode accNode = stateNode.get(address);
                long balance = getLong(accNode, "balance");
                long nonce = getLong(accNode, "nonce");

                if (accNode.has("code")) { // Contract account
                    // Contract account
                    String codeHex = getText(accNode, "code");
                    byte[] code = decodeHex(codeHex);

                    Map<String, String> storage = new HashMap<>();
                    JsonNode storageNode = accNode.get("storage");
                    if (storageNode != null) {
                        Iterator<String> storageKeys = storageNode.fieldNames();
                        while (storageKeys.hasNext()) {
                            String slot = storageKeys.next();
                            String value = storageNode.get(slot).asText();
                            storage.put(slot, value);
                        }
                    }
                    accounts.put(address, new ContractAccount(address, balance, code, storage, nonce));
                } else {
                    // EO account
                    try {
                        Long clientId = Crypto.getClientIdFromAddress(address);
                        PublicKey publicKey = Crypto.getClientPublicKey(clientId);
                        accounts.put(address, new EOAccount(address, balance, publicKey, nonce));
                    } catch (Exception e) {
                        throw new RuntimeException("Error loading account " + address + " from genesis file", e);
                    }
                }
            }

            Block genesisBlock = new Block(previousHash, blockHash, 0, transactions, accounts);
            executeBlock(genesisBlock);
            chain.add(genesisBlock);
            genesisBlock.persistToFile(storageDir + "/block_0.json");
        } catch (IOException e) {
            throw new RuntimeException("Error loading genesis block: " + GENESIS_PATH, e);
        }
    }

    // validate and append a block that has been decided by consensus
    public void addBlock(Block block) {
        validateBlock(block);
        executeBlock(block);
        chain.add(block);

        String fileName = storageDir + "/block_" + (chain.size() - 1) + ".json";
        try {
            block.persistToFile(fileName);
        } catch (IOException e) {
            throw new RuntimeException("Error writing block to file: " + fileName, e);
        }
    }

    private void executeBlock(Block block) {
        List<Transaction> transactions = block.getTransactions();

        for (Transaction tx : transactions) {
            ExecutionContext executionContext = new ExecutionContext(tx.getType(), tx.getGasLimit(), tx.getGasPrice());
            TransactionExecutor executor = new TransactionExecutor(tx, accounts, executionContext);
            boolean success = executor.execute();

            if (!success) {
                System.err.printf("TX %s failed (gas consumed: %d)%n", tx.getHash(), executionContext.getGasUsed());
            }
        }

        //TODO: update the state only for the accounts that were affected by this block
    }

    private void validateBlock(Block block) {
        Block lastBlock = chain.get(chain.size() - 1);

        if (!lastBlock.getBlockHash().equals(block.getPreviousBlockHash())) {
            throw new IllegalArgumentException("Block's previousBlockHash does not match hash of last block in chain");
        }

        if (block.getBlockNumber() != lastBlock.getBlockNumber() + 1) {
            throw new IllegalArgumentException("Block number not sequential: expected "
                    + (lastBlock.getBlockNumber() + 1) + ", got " + block.getBlockNumber());
        }

        if (!block.getBlockHash().equals(block.computeHash())) {
            throw new IllegalArgumentException("Block hash is invalid");
        }
    }

    public Account getAccount(String address) {
        return accounts.get(address);
    }

    public boolean accountExists(String address) {
        return accounts.containsKey(address);
    }

    public List<Block> getChain() {
        return new ArrayList<>(chain);
    }

    public Map<String, Account> getAccounts() {
        return new HashMap<>(accounts);
    }

    public Block getBlock(int blockNumber) {
        if (blockNumber < 0 || blockNumber >= chain.size()) {
            return null;
        }
        return chain.get(blockNumber);
    }

    public int getChainHeight() {
        return chain.size();
    }

    private String getText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private long getLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isNumber()) ? v.asLong() : 0L;
    }

    private byte[] decodeHex(String hex) {
        if (hex == null)
            return new byte[0];

        try {
            if (hex.startsWith("0x")) {
                hex = hex.substring(2);
            }
            return HexFormat.of().parseHex(hex);
        } catch (Exception e) {
            return new byte[0];
        }
    }

}
