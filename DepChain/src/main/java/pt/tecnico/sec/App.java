package pt.tecnico.sec;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.utils.MemberInfo;

import java.util.Scanner;

public class App 
{
    public static void main( String[] args ) 
    {
        if (args.length < 2) {
            System.err.println("Usage: mvn exec:java -Dexec.args=\"<ownId> <privateKeyPath>\"");
            return;
        }

        long ownId;
        String privateKeyPath;

        try {
            ownId = Long.parseLong(args[0]);
            privateKeyPath = args[1];
        } catch (NumberFormatException e) {
            System.err.println("Invalid ownId. It must be a number.");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        final AuthPerfLink apl;
        try {
            apl = new AuthPerfLink(ownId, privateKeyPath);
        } catch (Exception e) {
            System.err.println("Failed to initialize AuthPerfLink: " + e.getMessage());
            e.printStackTrace();
            scanner.close();
            return;
        }

        new Thread(() -> apl.receive()).start();

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
            System.out.println("4. Send data to a session");
            System.out.println("5. Exit");
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
                    System.out.println("Choose a session to send data to. Active Sessions:");
                    System.out.println(apl.seeActiveSessions());
                    System.out.print("Enter receiver ID: ");
                    String receiverIdStr = scanner.nextLine();
                    try {
                        long receiverID = Long.parseLong(receiverIdStr);
                        System.out.print("Enter the data to send: ");
                        String data = scanner.nextLine();
                        apl.sendData(apl.getSession(receiverID), data);
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid receiver ID. Please enter a valid number.");
                    } catch (Exception e) {
                        System.out.println("Failed to send data: " + e.getMessage());
                    }
                    break;

                case "5":
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
