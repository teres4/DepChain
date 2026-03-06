package pt.tecnico.sec.client;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.router.ApplicationMessageType;
import pt.tecnico.sec.router.DepChainMessage;
import pt.tecnico.sec.utils.Crypto;

public class Client {
    private int clientId;
    private int clientPort;
    private AuthPerfLink authPerfLink;
    private ClientOperations clientOps;
    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    public Client(int clientId, int clientPort) throws IOException, Exception {
        this.clientId = clientId;
        this.clientPort = clientPort;

        this.authPerfLink = new AuthPerfLink(this.clientId, Crypto.getClientPrivateKey(this.clientId));
        this.clientOps = new ClientOperations(this.clientId, this.authPerfLink);
        
        executorService.submit(() -> authPerfLink.receive());
        authPerfLink.setMessageListener((content) -> {
            try {
                DepChainMessage appMsg = DepChainMessage.fromBytes(content);
                if (appMsg.getMessageType() == ApplicationMessageType.COMMAND_RESPONSE) {
                    CommandResponse response = CommandResponse.fromJson(appMsg.getPayload());
                    clientOps.handleCommandResponse(response);
                }
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

        Client client = new Client(clientId, clientPort);

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        System.out.println("\n\u001B[36m" +
                " ██████╗██╗     ██╗███████╗███╗   ██╗████████╗\n" +
                "██╔════╝██║     ██║██╔════╝████╗  ██║╚══██╔══╝\n" +
                "██║     ██║     ██║█████╗  ██╔██╗ ██║   ██║   \n" +
                "██║     ██║     ██║██╔══╝  ██║╚██╗██║   ██║   \n" +
                "╚██████╗███████╗██║███████╗██║ ╚████║   ██║   \n" +
                " ╚═════╝╚══════╝╚═╝╚══════╝╚═╝  ╚═══╝   ╚═╝   \n" +
                "           DepChain Client v1.0" +
                "\u001B[0m");


        while (running) {
            System.out.println("\nPlease choose an option:");
            System.out.println("1. Append a message");
            System.out.println("2. Exit");
            System.out.print("Your choice: ");

            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    System.out.print("Enter the message to append: ");
                    String message = scanner.nextLine();
                    client.append(message);
                    break;

                case "2":
                    System.out.println("Exiting the application. Goodbye!");
                    running = false;
                    client.authPerfLink.shutdown();
                    break;

                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }

        scanner.close();
    }

    public void append(String message) {
        System.out.println("Client sending append request: " + message);
        try {
            this.clientOps.append(message);
            System.out.println("Append request sent successfully.");
        }
        catch (Exception e) {
            System.err.println("Error sending append request: " + e.getMessage());
        }
        
    }
}

