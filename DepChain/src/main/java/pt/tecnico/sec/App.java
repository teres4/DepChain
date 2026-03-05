package pt.tecnico.sec;

import java.util.Scanner;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.application.Application;
import pt.tecnico.sec.consensus.Consensus;
import pt.tecnico.sec.consensus.message.NewCommandMessage;
import pt.tecnico.sec.replica.ReplicaState;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;

public class App {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: mvn exec:java -Dexec.args=\"<ownId>\"");
            return;
        }

        long ownId;

        try {
            ownId = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid ownId. It must be a number.");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        final AuthPerfLink apl;
        try {
            apl = new AuthPerfLink(ownId, Crypto.getReplicaPrivateKey(ownId));
        } catch (Exception e) {
            System.err.println("Failed to initialize AuthPerfLink: " + e.getMessage());
            e.printStackTrace();
            scanner.close();
            return;
        }

        // Initialize replica state
        ReplicaState replicaState = new ReplicaState();

        Consensus consensus = new Consensus(
                ownId,
                apl.getMembers() != null ? apl.getMembers().length + 1 : 1,
                apl.getMemberManager(),
                replicaState);

        // Create application instance
        Application application = new Application(apl, consensus);

        // Set application in consensus to enable message sending
        consensus.setApplication(application);

        // Start the application main loop instead of consensus loop
        application.mainLoop();

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
                    System.out.println("Current replica state: \"" + replicaState.getState() + "\"");
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
