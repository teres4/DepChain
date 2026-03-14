
package pt.tecnico.sec.blockchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.account.EOAccount;

public class Blockchain {

    private final List<Block> chain = new ArrayList<>();

    // keep track of all accounts for transaction execution
    private final Map<String, Account> accounts = new HashMap<>();
    
    private final File storageDir;
    private static final String GENESIS_PATH = "config/block/genesis.json";

    public Blockchain(int memberId) {
        String blockStorageDir = "blocks/member_" + memberId;
        storageDir = new File(blockStorageDir);
        if (storageDir.exists()) {
            File[] files = storageDir.listFiles();
            if (files != null) {
                for (File file : files) file.delete();
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
                String from = txNode.get("from").asText();
                String to = txNode.get("to").asText(null);
                byte[] input = txNode.get("input").binaryValue();

                // TODO THESE FIELDS ARE NOT IN THE GENESIS FILE. 
                // UNSURE IF THEY SHOULD BE ADDED TO THE FILE OR REMOVED FROM THE TRANSACTION CLASS
                long amount = txNode.get("amount").asLong();
                long gasPrice = txNode.get("gas_price").asLong();
                long gasLimit = txNode.get("gas_limit").asLong();
                long nonce = txNode.get("nonce").asLong();
                transactions.add(new Transaction(from, to, amount, input, gasPrice, gasLimit, nonce, null));
            }

            // load accounts from genesis file
            JsonNode stateNode = genesisNode.get("state");
            Map<String, JsonNode> stateMap = mapper.convertValue(stateNode, new TypeReference<Map<String, JsonNode>>() {});

            for (Map.Entry<String, JsonNode> entry : stateMap.entrySet()) {
                String address = entry.getKey();
                JsonNode accountNode = entry.getValue();

                long balance = accountNode.get("balance").asLong();
                long nonce = accountNode.get("nonce").asLong();

                // TODO passing null public key for now since it's not in the genesis file 
                // also creating EOAccounts for all accounts since we don't know which ones are externally owned vs contract accounts at this point
                accounts.put(address, new EOAccount(address, balance, null, nonce));
            }

            Block genesisBlock = new Block(previousHash, blockHash, 0, transactions, accounts);
            chain.add(genesisBlock);
            genesisBlock.persistToFile(storageDir + "/block_0.json");

        } catch (IOException e) {
            throw new RuntimeException("Error loading genesis block: " + GENESIS_PATH, e);
        }
    }

    public void addBlock(Block block) {
        validateBlock(block);
        chain.add(block);

        String blockFileName = storageDir + "/block_" + (chain.size() - 1) + ".json";        
        try {
            block.persistToFile(blockFileName);
        } catch (IOException e) {
            throw new RuntimeException("Error writing block to file: " + blockFileName, e);
        }
    }

    private void validateBlock(Block block) {
        // TODO VALIDATE block structure, transactions, and state transitions before adding to chain
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

}
