package pt.tecnico.sec.client;

import java.security.PrivateKey;
import java.util.concurrent.ConcurrentLinkedQueue;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.client.message.CommandType;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;

public class ClientOperations {
    private final AuthPerfLink authPerfLink;
    private final int clientId;
    private final int numReplicas;
    private final int f; // Fault tolerance parameter
    private long nonce = 0; // To prevent replay attacks

    private final ConcurrentLinkedQueue<CommandResponse> currentResponses = new ConcurrentLinkedQueue<>();
    private long currentNonce = -1;

    public ClientOperations(int clientId, AuthPerfLink authPerfLink) {
        this.authPerfLink = authPerfLink;
        this.clientId = clientId;

        MemberInfo[] replicas = authPerfLink.getMembers();
        this.numReplicas = replicas.length;
        System.err.println("NUMBER OF REPLICAS " + numReplicas);
        this.f = (this.numReplicas - 1) / 3;
    }

    public void append(String stringToAppend) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);

        this.nonce++;
        String signableContent = CommandType.APPEND + ":" + this.clientId + ":" + stringToAppend + ":" + this.nonce;
        String signature = Crypto.signMessage(signableContent, clientPrivKey);

        Command appendCommand = new Command(CommandType.APPEND, this.clientId, stringToAppend, signature, this.nonce);
        String commandJson = appendCommand.toJson();

        // Clear previous responses and set current nonce
        currentResponses.clear();
        currentNonce = this.nonce;

        sendToAllReplicas(commandJson);
        waitForResponses(this.nonce);
    }

    private void sendToAllReplicas(String messageJson) {
        MemberInfo[] replicas = authPerfLink.getMembers();
        for (MemberInfo replica : replicas) {
            try {
                authPerfLink.sendData(replica.getId(), messageJson.getBytes());
            } catch (Exception e) {
                System.err.println("Failed to send to replica " + replica.getId() + ": " + e.getMessage());
            }
        }
    }

    public void handleCommandResponse(CommandResponse response) {
        // Only accept responses for the current command
        if (response.getNonce() == currentNonce) {
            currentResponses.add(response);
        }
    }

    // waits for f+1 matching successful responses from replicas to ensure
    // at least one honest replica executed the command
    private void waitForResponses(long nonce) throws Exception {
        int requiredResponses = this.f + 1; // Need f+1 = 2 responses

        // Wait until we get enough responses
        while (currentResponses.size() < requiredResponses) {
            Thread.sleep(100);
        }

        // Check if we have f+1 successful responses
        long successfulResponses = currentResponses.stream()
                .filter(CommandResponse::isSuccess)
                .count();

        if (successfulResponses < requiredResponses) {
            throw new Exception("Not enough successful responses. Got " + successfulResponses + " successful, need "
                    + requiredResponses);
        }

        // Print the result from one successful response
        CommandResponse successResponse = currentResponses.stream()
                .filter(CommandResponse::isSuccess)
                .findFirst()
                .orElseThrow();

        System.out.println("Command executed successfully. Result: " + successResponse.getResult());
    }
}