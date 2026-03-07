package pt.tecnico.sec.replica;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.Consensus;
import pt.tecnico.sec.router.MessageRouter;
import pt.tecnico.sec.utils.Crypto;

public class ReplicaServer {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println(
                    "Usage: mvn exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args=\"<replicaId>\"");
            return;
        }

        long replicaId;
        try {
            replicaId = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid replicaId. It must be a number.");
            return;
        }

        System.out.println("=================================================");
        System.out.println("       Starting Replica " + replicaId);
        System.out.println("=================================================");

        try {
            // Initialize AuthPerfLink
            AuthPerfLink apl = new AuthPerfLink(replicaId, Crypto.getReplicaPrivateKey(replicaId), 8080);

            // Initialize Consensus
            Consensus consensus = new Consensus(
                    replicaId,
                    apl.getMembers() != null ? apl.getMembers().length + 1 : 1);

            // Initialize Replica State and Replica
            ReplicaState replicaState = new ReplicaState();
            Replica replica = new Replica(replicaId, apl, consensus, replicaState);

            // Initialize MessageRouter
            MessageRouter router = new MessageRouter(apl, consensus, replica);
            consensus.setRouter(router);

            // Start message loop
            router.mainLoop();

            System.out.println("Replica " + replicaId + " started successfully.");
            System.out.println("Establishing connections with other replicas...");

            // Connect to all other replicas
            // apl.startConnectionWithAllMembers();

            // Wait for connections to establish
            Thread.sleep(2000);

            // Send initial NEW_VIEW to start consensus protocol
            // System.out.println("Sending initial NEW_VIEW to leader...");
            // consensus.startNewView();

            System.out.println("Replica " + replicaId + " ready to process commands.");

            // // Keep the server running
            // while (true) {
            // Thread.sleep(1000);
            // }

        } catch (Exception e) {
            System.err.println("Failed to start replica " + replicaId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
