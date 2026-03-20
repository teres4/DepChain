package pt.tecnico.sec;

import java.util.Scanner;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.blockchain.Blockchain;
import pt.tecnico.sec.consensus.ByzantineConsensus;
import pt.tecnico.sec.consensus.Consensus;
import pt.tecnico.sec.consensus.message.NewCommandMessage;
import pt.tecnico.sec.replica.Replica;
import pt.tecnico.sec.router.MessageRouter;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;

public class App {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: mvn exec:java -Dexec.args=\"<ownId>\"");
            return;
        }

        long ownId;
        boolean byzantine = false;

        try {
            ownId = Long.parseLong(args[0]);
            if (args.length >= 2)
                byzantine = args[1].equals("--byzantine");
        } catch (NumberFormatException e) {
            System.err.println("Invalid ownId.");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        final AuthPerfLink apl;
        try {
            apl = new AuthPerfLink(ownId, Crypto.getReplicaPrivateKey(ownId), 8080);
        } catch (Exception e) {
            System.err.println("Failed to initialize AuthPerfLink: " + e.getMessage());
            e.printStackTrace();
            scanner.close();
            return;
        }

        int totalReplicas = apl.getMembers() != null ? apl.getMembers().length + 1 : 1;

        Consensus consensus;
        if (byzantine) {
            ByzantineConsensus byz = new ByzantineConsensus(ownId, totalReplicas, null);

            System.out.println("\u001B[31m");
            System.out.println("BYZANTINE MODE SELECTOR:");
            System.out.println("1.  Silent (drop all outgoing messages)");
            System.out.println("2.  Conflicting (propose conflicting nodes to different replicas as leader)");
            System.out.println("3.  Wrong node (vote for fabricated node instead of the one in the QC)");
            System.out.println("4.  Wrong leader (send votes to the wrong leader of a certain view)");
            System.out.println("5.  Double vote (replay votes)");
            System.out.println("6.  Forged QC (leader fabricates quorum certificates with fake signatures)");
            System.out.println("7.  Spoof sender ID (impersonate another replica)");
            System.out.println("8.  Skip phase (leader skips PRE_COMMIT, jumps PREPARE -> COMMIT)");
            System.out.println("9.  Start as honest node");
            System.out.print("Select byzantine behaviour: ");
            System.out.println("\u001B[0m");

            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1" -> {
                    byz.setSilent(true);
                    System.out.println("[BYZANTINE] silent");
                }
                case "2" -> {
                    byz.setSendConflicting(true);
                    System.out.println("[BYZANTINE] conflicting");
                }
                case "3" -> {
                    byz.setVoteForWrongNode(true);
                    System.out.println("[BYZANTINE] wrongnode");
                }
                case "4" -> {
                    byz.setSendToWrongLeader(true);
                    System.out.println("[BYZANTINE] wrongleader");
                }
                case "5" -> {
                    byz.setDoubleVote(true);
                    System.out.println("[BYZANTINE] doublevote");
                }
                case "6" -> {
                    byz.setForgedQC(true);
                    System.out.println("[BYZANTINE] forgedQC");
                }
                case "7" -> {
                    byz.setSpoofSenderId(true);
                    System.out.println("[BYZANTINE] spoofSenderId");
                }
                case "8" -> {
                    byz.setSkipPhase(true);
                    System.out.println("[BYZANTINE] skipPhase");
                }
                case "9" -> {
                    /* Start as honest node */ }
                default -> System.err.println("Invalid choice, starting as honest node.");
            }
            consensus = byz;
        } else {
            consensus = new Consensus(ownId, totalReplicas, null);
        }

        Blockchain blockchain = new Blockchain(ownId);
        Replica replica = new Replica(ownId, apl, blockchain);

        MessageRouter router = new MessageRouter(apl, consensus, replica);
        consensus.setRouter(router);

        router.mainLoop();

        System.out.println("\n\u001B[32m" +
                "███████╗███████╗ ██████╗\n" +
                "██╔════╝██╔════╝██╔════╝\n" +
                "███████╗█████╗  ██║     \n" +
                "╚════██║██╔══╝  ██║     \n" +
                "███████║███████╗╚██████╗\n" +
                "╚══════╝╚══════╝ ╚═════╝\n" +
                "     SEC-PROJECT" +
                "\u001B[0m");

        while (running) {
            System.out.println("\nPlease choose an option:");
            System.out.println("1. View all members info");
            System.out.println("2. Start a new session");
            System.out.println("3. View active sessions");
            System.out.println("4. Start session with all members");
            System.out.println("5. Send command to consensus leader");
            System.out.println("6. View replica state");
            System.out.println("7. Exit");
            System.out.print("Your choice: ");

            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    // Retrieve and display member info
                    MemberInfo[] members = apl.getMembers();
                    if (members != null && members.length > 0) {
                        for (MemberInfo member : members) {
                            System.out.println(member);
                        }
                    } else {
                        System.out.println("No members available.");
                    }
                    break;

                case "2":
                    System.out.println("Choose member ID to start a session with. Available Members:");
                    MemberInfo[] availableMembers = apl.getMembers();
                    if (availableMembers != null && availableMembers.length > 0) {
                        for (MemberInfo member : availableMembers) {
                            System.out.println("ID: " + member.getId() + " Address: " + member.getAddr());
                        }
                        System.out.print("Enter member ID: ");
                        String memberIdStr = scanner.nextLine();
                        try {
                            long memberId = Long.parseLong(memberIdStr);
                            apl.startConnection(memberId);
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid member ID. Please enter a valid number.");
                        }
                    } else {
                        System.out.println("No members available to start a session with.");
                    }
                    break;

                case "3":
                    System.out.println(apl.seeActiveSessions());
                    break;

                case "4":
                    System.out.println("Starting session with all members.");
                    apl.startConnectionWithAllMembers();
                    break;

                case "5":
                    System.out.print("Enter command to send to consensus leader: ");
                    String command = scanner.nextLine();
                    NewCommandMessage newCommandMessage = new NewCommandMessage(1, 0, command);
                    try {
                        consensus.sendToSelf(newCommandMessage);
                    } catch (Exception e) {
                        System.out.println("Failed to send command to self: " + e.getMessage());
                    }
                    break;

                case "6":
                    System.out.println("Current replica state: \"" + replica.getBlockchain().toString() + "\"");
                    break;

                case "7":
                    System.out.println("Exiting the application. Goodbye!");
                    running = false;
                    apl.shutdown();
                    break;

                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }

        scanner.close();
    }
}
