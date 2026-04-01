package pt.tecnico.sec.replica;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import pt.tecnico.sec.account.Account;
import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.blockchain.Block;
import pt.tecnico.sec.blockchain.Blockchain;
import pt.tecnico.sec.blockchain.Transaction;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandType;

public class ByzantineBlockchainReplica extends Replica {

    private volatile boolean fabricateTransactions;
    private volatile boolean malformedBlocks;
    private volatile boolean modifyClientTransactions;

    private final List<Transaction> modifiedTransactions = new CopyOnWriteArrayList<>();
    private final AtomicLong fabricatedNonce = new AtomicLong(0);

    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    public ByzantineBlockchainReplica(long replicaId, AuthPerfLink authPerfLink, Blockchain blockchain) {
        super(replicaId, authPerfLink, blockchain);
    }

    public void setFabricateTransactions(boolean fabricateTransactions) {
        this.fabricateTransactions = fabricateTransactions;
        logByzantine("fabricateTransactions = " + fabricateTransactions);
    }

    public void setMalformedBlocks(boolean malformedBlocks) {
        this.malformedBlocks = malformedBlocks;
        logByzantine("malformedBlocks = " + malformedBlocks);
    }

    public void setModifyClientTransactions(boolean modifyClientTransactions) {
        this.modifyClientTransactions = modifyClientTransactions;
        logByzantine("modifyClientTransactions = " + modifyClientTransactions);
    }

    @Override
    public void handleClientTransaction(Command request) {
        if (modifyClientTransactions && request.getType() == CommandType.TRANSFERDEPCOIN) {
            try {
                Transaction original = request.getTransaction();
                Transaction modified = modifiedCopy(original);
                modifiedTransactions.add(modified);
                logByzantine("Captured and modified client tx " + original.getHash()
                        + " -> " + modified.getHash());
            } catch (Exception e) {
                logByzantine("Failed to modify incoming transaction: " + e.getMessage());
            }
        }

        // Keep normal behavior so this replica can still acknowledge clients.
        super.handleClientTransaction(request);
    }

    @Override
    public Block buildBlock() {
        Block honestCandidate = super.buildBlock();

        if (!fabricateTransactions && !malformedBlocks && modifiedTransactions.isEmpty()) {
            return honestCandidate;
        }

        Blockchain chain = getBlockchain();
        Block lastBlock = chain.getBlock(chain.getChainHeight() - 1);
        Map<String, Account> currentState = chain.getAccounts();
        List<Transaction> txs = new ArrayList<>();

        if (honestCandidate != null) {
            txs.addAll(honestCandidate.getTransactions());
        }

        if (!modifiedTransactions.isEmpty()) {
            txs.add(modifiedTransactions.remove(0));
            logByzantine("Injecting modified client transaction into proposed block");
        }

        if (fabricateTransactions) {
            txs.add(fabricatedTransaction());
            logByzantine("Injecting fabricated transaction into proposed block");
        }

        if (txs.isEmpty()) {
            return null;
        }

        if (malformedBlocks) {
            String badPrevHash = "BYZANTINE_BAD_PREV_" + System.nanoTime();
            long badNumber = lastBlock.getBlockNumber() + 2;
            String forgedHash = "BYZANTINE_FORGED_HASH";
            logByzantine("Proposing malformed block (bad prevHash + bad number + forged hash)");
            return new Block(badPrevHash, forgedHash, badNumber, txs, currentState);
        }

        return new Block(lastBlock.getBlockHash(), lastBlock.getBlockNumber() + 1, txs, currentState);
    }

    private Transaction modifiedCopy(Transaction original) {
        String attackedRecipient = "0x000000000000000000000000000000000000dEaD";
        long inflatedAmount = original.getAmount() + 1;
        return new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                original.getFrom(),
                attackedRecipient,
                inflatedAmount,
                original.getInput(),
                original.getGasPrice(),
                original.getGasLimit(),
                original.getNonce(),
                original.getSignature());
    }

    private Transaction fabricatedTransaction() {
        return new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                "0x000000000000000000000000000000000000badd",
                "0x000000000000000000000000000000000000c0de",
                999_999,
                new byte[0],
                1,
                21_000,
                fabricatedNonce.getAndIncrement(),
                new byte[] { 1, 2, 3 });
    }

    private void logByzantine(String message) {
        System.out.println(RED + "[ByzantineBlockchainReplica] " + message + RESET);
    }
}
