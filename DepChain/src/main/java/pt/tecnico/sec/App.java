package pt.tecnico.sec;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.apl.Session;
import pt.tecnico.sec.apl.SessionManager;
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

        AuthPerfLink apl = null;
        try {
            apl = new AuthPerfLink(ownId, privateKeyPath);
        } catch (Exception e) {
            System.err.println("Failed to initialize AuthPerfLink: " + e.getMessage());
            e.printStackTrace();
            scanner.close();
            return;
        }

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
            System.out.println("2. Exit");
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
                    System.out.println("Exiting the application. Goodbye!");
                    running = false;
                    break;

                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }

        scanner.close();
    }
}
