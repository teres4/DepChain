package pt.tecnico.sec.replica;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.blockchain.Blockchain;
import pt.tecnico.sec.consensus.ByzantineConsensus;
import pt.tecnico.sec.consensus.Consensus;
import pt.tecnico.sec.consensus.Consensus.CommandValidator;
import pt.tecnico.sec.router.MessageRouter;
import pt.tecnico.sec.utils.Crypto;

public class ReplicaServer {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println(
                    "Usage: mvn exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args=\"<replicaId> [byzantineMode] [byzantineReplicaMode]\"");
            System.err.println(
                    "  byzantineMode: 0=honest (default), 1=silent, 2=conflicting, 3=wrongNode, 4=wrongLeader, 5=doubleVote, 6=forgedQC, 7=spoofSender, 8=skipPhase");
            System.err.println(
                    "  byzantineReplicaMode: 0=honest (default), 1=fabricateTxs, 2=malformedBlocks, 3=modifyClientTxs, 4=all");
            return;
        }

        long replicaId;
        try {
            replicaId = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid replicaId. It must be a number.");
            return;
        }

        int byzantineMode = 0;
        if (args.length >= 2) {
            try {
                byzantineMode = Integer.parseInt(args[1]);
                if (byzantineMode < 0 || byzantineMode > 8) {
                    System.err.println("Invalid byzantineMode. Must be between 0 and 8.");
                    return;
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid byzantineMode. It must be a number.");
                return;
            }
        }

        int byzantineReplicaMode = 0;
        if (args.length >= 3) {
            try {
                byzantineReplicaMode = Integer.parseInt(args[2]);
                if (byzantineReplicaMode < 0 || byzantineReplicaMode > 4) {
                    System.err.println("Invalid byzantineReplicaMode. Must be between 0 and 4.");
                    return;
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid byzantineReplicaMode. It must be a number.");
                return;
            }
        }

        System.out.println("=================================================");
        System.out.println("       Starting Replica " + replicaId);
        System.out.println("=================================================");

        try {
            // Initialize AuthPerfLink
            AuthPerfLink apl = new AuthPerfLink(replicaId, Crypto.getReplicaPrivateKey(replicaId), 8080);

            Blockchain blockchain = new Blockchain(replicaId);
            Replica replica = createReplica(replicaId, apl, blockchain, byzantineReplicaMode);

            Consensus consensus = createConsensus(replicaId, apl, byzantineMode,
                    blk -> replica.validateBlock(blk, false));

            MessageRouter router = new MessageRouter(apl, consensus, replica);
            consensus.setRouter(router);

            // Start message loop
            router.mainLoop();

            System.out.println("Replica " + replicaId + " started successfully.");
            System.out.println("Establishing connections with other replicas...");

            // Connect to all other replicas
            apl.startConnectionWithAllMembers();

            // Wait for connections to establish
            Thread.sleep(1000);

            System.out.println("All connections established. Starting consensus protocol...");

            consensus.startNewView();

            System.out.println("Replica " + replicaId + " ready to process commands.");

        } catch (Exception e) {
            System.err.println("Failed to start replica " + replicaId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Consensus createConsensus(long replicaId, AuthPerfLink apl, int mode,
            CommandValidator commandValidator) {
        int totalReplicas = apl.getMembers() != null ? apl.getMembers().length + 1 : 1;
        if (mode == 0) {
            return new Consensus(replicaId, totalReplicas, commandValidator);
        }
        ByzantineConsensus bc = new ByzantineConsensus(replicaId, totalReplicas, commandValidator);
        switch (mode) {
            case 1 -> bc.setSilent(true);
            case 2 -> bc.setSendConflicting(true);
            case 3 -> bc.setVoteForWrongNode(true);
            case 4 -> bc.setSendToWrongLeader(true);
            case 5 -> bc.setDoubleVote(true);
            case 6 -> bc.setForgedQC(true);
            case 7 -> bc.setSpoofSenderId(true);
            case 8 -> bc.setSkipPhase(true);
        }
        return bc;
    }

    private static Replica createReplica(long replicaId, AuthPerfLink apl, Blockchain blockchain, int mode) {
        if (mode == 0) {
            return new Replica(replicaId, apl, blockchain);
        }

        ByzantineBlockchainReplica byz = new ByzantineBlockchainReplica(replicaId, apl, blockchain);
        switch (mode) {
            case 1 -> byz.setFabricateTransactions(true);
            case 2 -> byz.setMalformedBlocks(true);
            case 3 -> byz.setModifyClientTransactions(true);
            case 4 -> {
                byz.setFabricateTransactions(true);
                byz.setMalformedBlocks(true);
                byz.setModifyClientTransactions(true);
            }
            default -> {
                return new Replica(replicaId, apl, blockchain);
            }
        }
        return byz;
    }
}
