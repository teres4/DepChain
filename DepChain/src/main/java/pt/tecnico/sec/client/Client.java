package pt.tecnico.sec.client;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.utils.Crypto;

public class Client {
    private int clientId;
    private int clientPort;
    private AuthPerfLink authPerfLink;
    private static ClientOperations clientOps;
    private long GasPrice = 1; // Default gas price
    private long GasLimit = 100000; // Default gas limit
    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    public Client(int clientId, int clientPort) throws IOException, Exception {
        this.clientId = clientId;
        this.clientPort = clientPort;

        this.authPerfLink = new AuthPerfLink(this.clientId, Crypto.getClientPrivateKey(this.clientId), false,
                clientPort);
        clientOps = new ClientOperations(this.clientId, this.authPerfLink);

        executorService.submit(() -> authPerfLink.receive());
        authPerfLink.setConsensusMessageListener((content) -> {
            try {
                CommandResponse response = CommandResponse.fromJson(content);
                clientOps.handleCommandResponse(response);

            } catch (Exception e) {
                System.err.println("[CLIENT] Failed to handle message: " + e.getMessage());
            }
        });

        authPerfLink.connectToAllReplicas();

    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: mvn exec:java -Dexec.args=\"<clientId> <clientPort>\"");
            return;
        }

        int clientId = Integer.parseInt(args[0]);
        int clientPort = Integer.parseInt(args[1]);

        System.err.println("Starting Client with ID: " + clientId + " on port: " + clientPort);
        Client client = new Client(clientId, clientPort);

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        System.out.println("=================================================");
        System.out.println("       Starting Client " + clientId);
        System.out.println("=================================================");

        while (running) {
            System.out.println("\nPlease choose an option:");
            System.out.println("1. Transfer DepCoin");
            System.out.println("2. Transfer IST Coin");
            System.out.println("3. Approve IST Coin Allowance");
            System.out.println("4. Transfer IST Coin From (transferFrom)");
            System.out.println("=================================================");
            System.out.println("5. Get Balance");
            System.out.println("6. Get History");
            System.out.println("=================================================");
            System.out.println("7. Change default gas price and limit");
            System.out.println("=================================================");
            System.out.println("8. Exit");
            System.out.print("Your choice: ");
            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    client.transferDepCoin(scanner);
                    break;

                case "2":
                    client.transferISTCoin(scanner);
                    break;

                case "3":
                    client.approveISTCoin(scanner);
                    break;

                case "4":
                    client.transferFromISTCoin(scanner);
                    break;

                case "5":
                    System.out.println("Get Balance functionality is not implemented yet.");
                    break;

                case "6":
                    System.out.println("Get History functionality is not implemented yet.");
                    break;

                case "7":
                    System.out.println("Gas price:");
                    long gasPrice = Long.parseLong(scanner.nextLine());
                    System.out.println("Gas limit:");
                    long gasLimit = Long.parseLong(scanner.nextLine());
                    if (gasPrice <= 0 || gasLimit <= 0) {
                        System.out.println("Cheap! Gas price and limit must be positive numbers.");
                    } else {
                        client.GasPrice = gasPrice;
                        client.GasLimit = gasLimit;
                        System.out.println("Gas parameters updated successfully.");
                    }
                    break;

                case "8":
                    System.out.println("Exiting the application. Baibai!");
                    running = false;
                    client.authPerfLink.shutdown();
                    break;

                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    public void transferDepCoin(Scanner scanner) {
        System.out.print("Enter the recipient client ID (e.g., 101): ");
        try {
            int recipientId = Integer.parseInt(scanner.nextLine());

            if (!Crypto.clientKeysExist(recipientId)) {
                System.err.println("Unknown recipient client ID: " + recipientId);
                System.err.println("Configured client IDs: " + Crypto.getConfiguredClientIds());
                return;
            }

            System.out.print("Enter the amount of DepCoin to transfer: ");
            int amount = Integer.parseInt(scanner.nextLine());
            clientOps.transferDepCoin(recipientId, amount, this.GasPrice, this.GasLimit);
            System.out.println("Transfer request sent successfully.");
        } catch (NumberFormatException e) {
            System.err.println("Invalid input. Please enter numeric values for recipient ID and amount.");
        } catch (Exception e) {
            System.err.println("Error sending transfer request: " + e.getMessage());
        }
    }

    public void transferISTCoin(Scanner scanner) {
        System.out.print("Enter the recipient client ID (e.g., 101): ");
        try {
            int recipientId = Integer.parseInt(scanner.nextLine());

            if (!Crypto.clientKeysExist(recipientId)) {
                System.err.println("Unknown recipient client ID: " + recipientId);
                System.err.println("Configured client IDs: " + Crypto.getConfiguredClientIds());
                return;
            }

            System.out.print("Enter the amount of IST Coin to transfer: ");
            int amount = Integer.parseInt(scanner.nextLine());
            clientOps.transferISTCoin(recipientId, amount, this.GasPrice, this.GasLimit);
            System.out.println("Transfer request sent successfully.");
        } catch (NumberFormatException e) {
            System.err.println("Invalid input. Please enter numeric values for recipient ID and amount.");
        } catch (Exception e) {
            System.err.println("Error sending transfer request: " + e.getMessage());
        }
    }

    public void approveISTCoin(Scanner scanner) {
        System.out.print("Enter the spender client ID (e.g., 101): ");
        try {
            int spenderId = Integer.parseInt(scanner.nextLine());
            if (!Crypto.clientKeysExist(spenderId)) {
                System.err.println("Unknown spender client ID: " + spenderId);
                return;
            }
            System.out.print("Enter the expected current allowance (0 if none): ");
            long expectedCurrent = Long.parseLong(scanner.nextLine());
            System.out.print("Enter the new allowance amount: ");
            long newAllowance = Long.parseLong(scanner.nextLine());
            clientOps.approveISTCoin(spenderId, expectedCurrent, newAllowance, this.GasPrice, this.GasLimit);
            System.out.println("SafeApprove request sent successfully.");
        } catch (NumberFormatException e) {
            System.err.println("Invalid input.");
        } catch (Exception e) {
            System.err.println("Error sending approve request: " + e.getMessage());
        }
    }

    public void transferFromISTCoin(Scanner scanner) {
        System.out.print("Enter the owner client ID (whose tokens to spend): ");
        try {
            int ownerId = Integer.parseInt(scanner.nextLine());
            if (!Crypto.clientKeysExist(ownerId)) {
                System.err.println("Unknown owner client ID: " + ownerId);
                return;
            }
            System.out.print("Enter the recipient client ID: ");
            int recipientId = Integer.parseInt(scanner.nextLine());
            if (!Crypto.clientKeysExist(recipientId)) {
                System.err.println("Unknown recipient client ID: " + recipientId);
                return;
            }
            System.out.print("Enter the amount of IST Coin to transfer: ");
            int amount = Integer.parseInt(scanner.nextLine());
            clientOps.transferFromISTCoin(ownerId, recipientId, amount, this.GasPrice, this.GasLimit);
            System.out.println("TransferFrom request sent successfully.");
        } catch (NumberFormatException e) {
            System.err.println("Invalid input.");
        } catch (Exception e) {
            System.err.println("Error sending transferFrom request: " + e.getMessage());
        }
    }
}
