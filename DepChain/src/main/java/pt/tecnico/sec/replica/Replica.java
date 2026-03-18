package pt.tecnico.sec.replica;

import java.security.PublicKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.utils.Crypto;

public class Replica {
    private final long replicaId;
    private final AuthPerfLink authPerfLink;
    private final ReplicaState state;

    private final ConcurrentLinkedQueue<String> pendingCommands = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, Long> clientNonces = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(100);

    public Replica(long replicaId, AuthPerfLink authPerfLink, ReplicaState state) {
        this.replicaId = replicaId;
        this.authPerfLink = authPerfLink;
        this.state = state;
    }

    public void handleClientCommand(Command command) {
        Long clientID = command.getSenderId();
        Long nonce = command.getNonce();
        // Check for replay attack
        if (clientNonces.containsKey(clientID) && nonce <= clientNonces.get(clientID)) {
            System.out.println("Rejected replayed command from client " + clientID + " with nonce " + nonce);
            return;
        }

        // check signature
        if (!checkCommandSignature(command)) {
            System.out.println("Rejected command with invalid signature from client " + clientID);
            return;
        }
        clientNonces.put(clientID, nonce); // Update latest nonce for this client
        pendingCommands.add(command.toJson());
    }

    public String getCommandToPropose() {
        return pendingCommands.peek();
    }

    public void commandDecided(String commandJson) {
        pendingCommands.remove(commandJson);

        // Handle NO-OP commands (used when no real command is available)
        if ("NO-OP".equals(commandJson)) {
            System.out.println("NO-OP command decided, skipping execution");
            return;
        }

        try {
            if (!validateCommand(commandJson, true)) 
                return; // Invalid command, response already sent in validateCommand
        
            boolean success = state.executeCommand(commandJson);

            Command command = Command.fromJson(commandJson);
            Long clientID = command.getSenderId();
            Long nonce = command.getNonce();

            // Process result
            if (success) {
                System.out.println("Command executed successfully. Current state: " + state.getState());
                sendResponseToClient(clientID, nonce, true, state.getState());
            } else {
                sendResponseToClient(clientID, nonce, false, state.getState());
            }
        } catch (Exception e) {
            System.err.println("Failed to process decided command: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendResponseToClient(long clientId, long nonce, boolean success, String result) {
        try {
            CommandResponse response = new CommandResponse(clientId, nonce, success, result);

            executorService.submit(() -> {
                try {
                    authPerfLink.sendData(clientId, response.toJson().getBytes());
                } catch (Exception e) {
                    System.err.println("Failed to send response to client: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to create response: " + e.getMessage());
        }
    }

    private boolean checkCommandFormat(String commandJson) {
        try {
            Command cmd = Command.fromJson(commandJson);
            return cmd.getType() != null && cmd.getSenderId() > 0 && cmd.getNonce() >= 0
                    && checkCommandSignature(cmd);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkCommandSignature(Command cmd) {
        String contentToVerify = cmd.getType().toString() + ":" + cmd.getSenderId() + ":"
                + cmd.getContent() + ":" + cmd.getNonce();
        try {
            PublicKey clientPubKey = Crypto.getClientPublicKey(cmd.getSenderId());
            return Crypto.verifySignature(contentToVerify.getBytes(), cmd.getSignature(), clientPubKey);
        } catch (Exception e) {
            System.err.println("Failed to verify command signature: " + e.getMessage());
            return false;
        }
    }

    public boolean validateCommand(String commandJson, boolean sendResponse) {
        Command command = Command.fromJson(commandJson);
        Long clientID = command.getSenderId();
        Long nonce = command.getNonce();

        // Validate format
        if (!checkCommandFormat(commandJson)) {
            System.out.println("Command has invalid format");
            if (sendResponse) {
                sendResponseToClient(clientID, nonce, false, "Invalid command format");
            }
            return false;
        }

        // Validate signature (defense in depth)
        if (!checkCommandSignature(command)) {
            System.out.println("Command has invalid signature");
            if (sendResponse) {
                sendResponseToClient(clientID, nonce, false, "Invalid signature");
            }
            return false;
        }

        // Check for replay attacks
        if (clientNonces.containsKey(clientID) && nonce < clientNonces.get(clientID)) {
            System.out.println("Rejected replayed command from client " + clientID + " with nonce " + nonce);
            if (sendResponse) {
                sendResponseToClient(clientID, nonce, false, "Replay attack detected");
            }
            return false;
        }
        return true;
    }

    public long getReplicaId() {
        return replicaId;
    }

    public ReplicaState getState() {
        return state;
    }
}
