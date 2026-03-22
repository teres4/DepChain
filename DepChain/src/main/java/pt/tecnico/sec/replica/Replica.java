package pt.tecnico.sec.replica;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.blockchain.Block;
import pt.tecnico.sec.blockchain.Blockchain;
import pt.tecnico.sec.blockchain.Transaction;
import pt.tecnico.sec.client.message.*;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.utils.Crypto;

public class Replica {
    private final long replicaId;
    private final AuthPerfLink authPerfLink;
    private final Blockchain blockchain;

    // Mempool: sender address -> (nonce -> transaction)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Transaction>> mempool = new ConcurrentHashMap<>();

    // Pending transactions: tracks (sender:nonce) pairs for txs selected but not
    // yet decided
    private final java.util.Set<String> pendingTransactions = ConcurrentHashMap.newKeySet();

    private final ExecutorService executorService = Executors.newFixedThreadPool(100);

    public Replica(long replicaId, AuthPerfLink authPerfLink, Blockchain blockchain) {
        this.replicaId = replicaId;
        this.authPerfLink = authPerfLink;
        this.blockchain = blockchain;
    }

    public long getReplicaId() {
        return replicaId;
    }

    public Blockchain getBlockchain() {
        return blockchain;
    }

    public void handleClientTransaction(Command request) {
        Transaction tx = request.getTransaction();
        String sender = tx.getFrom().toLowerCase();

        if (!verifyTransactionSignature(tx)) {
            System.err.println("Rejected tx with invalid signature from " + sender);
            sendResponseToClient(request.getSenderId(), tx.getNonce(), false, "Invalid signature");
            return;
        }

        Account account = blockchain.getAccount(sender);
        if (account == null) {
            System.err.println("Rejected tx from unknown sender: " + sender);
            sendResponseToClient(request.getSenderId(), tx.getNonce(), false, "Unknown sender");
            return;
        }

        if (account.getBalance() < tx.getGasLimit() * tx.getGasPrice()) {
            System.err.println("Rejected tx from " + sender + " due to insufficient balance.");
            sendResponseToClient(request.getSenderId(), tx.getNonce(), false,
                    "Insufficient balance to cover max transaction cost");
            return;
        }

        if (tx.getNonce() < account.getNonce()) {
            System.err.println("Rejected tx with stale nonce from " + sender);
            sendResponseToClient(request.getSenderId(), tx.getNonce(), false, "Stale nonce");
            return;
        }

        addToMempool(tx);
    }

    private void addToMempool(Transaction tx) {
        String sender = tx.getFrom();

        ConcurrentHashMap<Long, Transaction> senderTxs = mempool.computeIfAbsent(sender,
                k -> new ConcurrentHashMap<>());

        Transaction existing = senderTxs.get(tx.getNonce());
        // fee bumping
        if (existing != null) {
            // Replacement: require at least 10% higher gasPrice to prevent spam
            long minBump = existing.getGasPrice() * 110 / 100;
            if (tx.getGasPrice() < minBump) {
                System.err.printf("Rejected replacement tx: gasPrice %d < required %d%n", tx.getGasPrice(), minBump);
                return;
            }
            System.out.printf("Replacing tx nonce=%d for %s (old gasPrice=%d new gasPrice=%d)%n",
                    tx.getNonce(), sender, existing.getGasPrice(), tx.getGasPrice());
        }
        logger("Added tx to mempool: " + tx.getHash() + " from " + sender + " nonce " + tx.getNonce()
                + " gasPrice " + tx.getGasPrice() + " gasLimit " + tx.getGasLimit());
        senderTxs.put(tx.getNonce(), tx);
    }

    public Block buildBlock() {
        Map<String, Account> currentAccounts = blockchain.getAccounts();
        List<Transaction> selected = selectTransactionsForBlock(currentAccounts);

        if (selected.isEmpty())
            return null;

        Block lastBlock = blockchain.getBlock(blockchain.getChainHeight() - 1);
        Block proposed = new Block(lastBlock.getBlockHash(), lastBlock.getBlockNumber() + 1,
                selected, currentAccounts);

        logger("Built block proposal with " + selected.size() + " transactions");
        return proposed;
    }

    private List<Transaction> selectTransactionsForBlock(Map<String, Account> accounts) {
        List<Transaction> executable = new ArrayList<>();

        for (Map.Entry<String, ConcurrentHashMap<Long, Transaction>> entry : mempool.entrySet()) {
            String sender = entry.getKey();
            Account account = accounts.get(sender);
            if (account == null)
                continue;

            // only the next expected nonce is executable right now
            Transaction tx = entry.getValue().get(account.getNonce());
            if (tx != null && !isPending(sender, tx.getNonce())) {
                executable.add(tx);
            }
        }

        executable.sort(Comparator.comparingLong((Transaction tx) -> tx.getGasPrice() * tx.getGasLimit()).reversed());

        List<Transaction> selected = new ArrayList<>();
        long gasAccumulated = 0;

        for (Transaction tx : executable) {
            if (gasAccumulated + tx.getGasLimit() > Blockchain.BLOCK_GAS_LIMIT)
                break;
            selected.add(tx);
            gasAccumulated += tx.getGasLimit();
            // Mark transaction as pending (part of a block proposal)
            markPending(tx.getFrom(), tx.getNonce());
        }
        return selected;
    }

    public void onBlockDecided(String command) {
        try {

            if (command.equals("NO-OP")) {
                logger("Decided NO-OP block, skipping state update");
                return;
            }

            Block decidedBlock = Block.fromJson(command);

            blockchain.addBlock(decidedBlock);
            removeExecutedFromMempool(decidedBlock);

            notifyClient(decidedBlock);

        } catch (Exception e) {
            System.err.println("Failed to add decided block to state: " + e.getMessage());
        }
    }

    // remove transactions that were included in the decided block from the mempool
    private void removeExecutedFromMempool(Block block) {
        Map<String, Account> updatedAccounts = blockchain.getAccounts();

        for (Transaction tx : block.getTransactions()) {
            String sender = tx.getFrom().toLowerCase();
            ConcurrentHashMap<Long, Transaction> senderTxs = mempool.get(sender);
            if (senderTxs == null)
                continue;

            // Remove the confirmed nonce
            senderTxs.remove(tx.getNonce());
            // Remove from pending since it's now decided
            removeFromPending(sender, tx.getNonce());

            // clean up any other pending transactions that are now invalid due to nonce gap
            Account account = updatedAccounts.get(sender);
            if (account != null) {
                senderTxs.entrySet().removeIf(e -> e.getKey() < account.getNonce());
            }

            if (senderTxs.isEmpty())
                mempool.remove(sender);
        }
    }

    private void notifyClient(Block block) {
        if (block != null) {
            for (Transaction tx : block.getTransactions()) {
                String sender = tx.getFrom().toLowerCase();
                try {
                    long clientId = Crypto.getClientIdFromAddress(sender);
                    String result = "Transaction included in block " + block.getBlockNumber() + "\nTransfer amount: " + tx.getAmount() + "\nGas used: " + tx.getGasUsed() + "\nCurrent balance: " + blockchain.getAccount(sender).getBalance();
                    sendResponseToClient(clientId, tx.getNonce(), true, result);
                } catch (Exception e) {
                    System.err.println("Failed to notify client for transaction " + tx.getHash() + ": " + e.getMessage());
                }
            }
        }
    }

    private void sendResponseToClient(long clientId, long nonce, boolean success, String result) {
        try {
            CommandResponse response = new CommandResponse(clientId, nonce, success, result);

            executorService.submit(() -> {
                try {
                    authPerfLink.sendData(clientId, response.toJson().getBytes());
                } catch (Exception e) {
                    System.err.println("Failed to send response to client: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to create response: " + e.getMessage());
        }
    }

    private boolean verifyTransactionSignature(Transaction tx) {
        try {
            if (tx.getSignature() == null || tx.getSignature().length == 0) {
                return false;
            }

            String signablePayload = Base64.getEncoder().encodeToString(tx.toSignableBytes());
            String signatureBase64 = Base64.getEncoder().encodeToString(tx.getSignature());

            return Crypto.verifySignatureEOA(
                    signablePayload.getBytes(StandardCharsets.UTF_8),
                    signatureBase64,
                    tx.getFrom());
        } catch (Exception e) {
            System.err.println("Signature verification failed: " + e.getMessage());
            return false;
        }
    }

    public boolean validateBlock(String blockJson, boolean sendResponse) {
        try {
            Block block = Block.fromJson(blockJson);

            // Structural checks
            if (block.getPreviousBlockHash() == null || block.getPreviousBlockHash().isBlank()) {
                System.err.println("Block is missing previousBlockHash");
                return false;
            }

            if (block.getBlockHash() == null || block.getBlockHash().isBlank()) {
                System.err.println("Block is missing blockHash");
                return false;
            }

            if (block.getTransactions() == null) {
                System.err.println("Block is missing transactions list");
                return false;
            }

            if (block.getState() == null) {
                System.err.println("Block is missing state map");
                return false;
            }

            if (block.getBlockNumber() < 0) {
                System.err.println("Block has invalid (negative) blockNumber");
                return false;
            }

            // Hash integrity
            if (!block.getBlockHash().equals(block.computeHash())) {
                System.err.println("Block hash mismatch for block " + block.getBlockNumber());
                return false;
            }

            // Chain continuity
            Block lastBlock = blockchain.getBlock(blockchain.getChainHeight() - 1);
            if (!block.getPreviousBlockHash().equals(lastBlock.getBlockHash())) {
                System.err.println("Previous block hash mismatch for block " + block.getBlockNumber());
                return false;
            }
            if (block.getBlockNumber() != lastBlock.getBlockNumber() + 1) {
                System.err.println("Non-sequential block number: expected " + (lastBlock.getBlockNumber() + 1)
                        + " but got " + block.getBlockNumber());
                return false;
            }

            // Transaction checks
            Map<String, Long> expectedNonces = new ConcurrentHashMap<>();
            long previousFee = Long.MAX_VALUE;
            for (Transaction tx : block.getTransactions()) {
                if (!validateTransaction(tx, block.getState(), expectedNonces, previousFee)) {
                    return false;
                }
                previousFee = tx.getGasPrice() * tx.getGasLimit();
                expectedNonces.put(tx.getFrom().toLowerCase(),
                        expectedNonces.getOrDefault(tx.getFrom().toLowerCase(),
                                block.getState().get(tx.getFrom().toLowerCase()).getNonce()) + 1);
            }

            return true;

        } catch (Exception e) {
            System.err.println("Block validation failed: " + e.getMessage());
            return false;
        }
    }

    private boolean validateTransaction(Transaction tx, Map<String, Account> state,
            Map<String, Long> expectedNonces, long previousFee) {
        if (tx == null) {
            System.err.println("Block contains a null transaction");
            return false;
        }
        if (tx.getHash() == null || tx.getHash().isBlank()) {
            System.err.println("Transaction is missing its hash");
            return false;
        }
        if (tx.getFrom() == null || tx.getFrom().isBlank()) {
            System.err.println("Transaction " + tx.getHash() + " is missing sender (from)");
            return false;
        }

        if (tx.getGasPrice() <= 0 || tx.getGasLimit() <= 0) {
            System.err.println("Transaction " + tx.getHash() + " has invalid gasPrice or gasLimit");
            return false;
        }

        long fee = tx.getGasPrice() * tx.getGasLimit();
        if (fee > previousFee) {
            System.err.println("Transaction ordering violation: tx " + tx.getHash()
                    + " has fee " + fee + " but previous tx had fee " + previousFee);
            return false;
        }

        if (!verifyTransactionSignature(tx)) {
            System.err.println("Invalid signature for transaction " + tx.getHash());
            return false;
        }

        String sender = tx.getFrom().toLowerCase();
        Account senderAccount = state.get(sender);
        if (senderAccount == null) {
            System.err.println("Unknown sender in transaction: " + sender);
            return false;
        }

        long expectedNonce = expectedNonces.getOrDefault(sender, senderAccount.getNonce());
        if (tx.getNonce() != expectedNonce) {
            System.err.println("Nonce mismatch for sender " + sender
                    + ": expected " + expectedNonce + " but got " + tx.getNonce());
            return false;
        }

        return true;
    }

    private boolean isPending(String sender, long nonce) {
        return pendingTransactions.contains(sender.toLowerCase() + ":" + nonce);
    }

    private void markPending(String sender, long nonce) {
        pendingTransactions.add(sender.toLowerCase() + ":" + nonce);
    }

    private void removeFromPending(String sender, long nonce) {
        pendingTransactions.remove(sender.toLowerCase() + ":" + nonce);
    }

    // Call this if a block proposal is rejected/fails to get decided
    public void onBlockRejected(Block block) {
        for (Transaction tx : block.getTransactions()) {
            removeFromPending(tx.getFrom(), tx.getNonce());
        }
    }

    private void logger(String message) {
        // print in green color
        System.out.println("\u001B[32m" + message + "\u001B[0m");
    }
}
