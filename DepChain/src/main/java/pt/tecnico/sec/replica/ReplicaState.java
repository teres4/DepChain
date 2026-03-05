package pt.tecnico.sec.replica;

import java.security.PublicKey;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.utils.Crypto;

public class ReplicaState {

    private final List<Map.Entry<Long, String>> state;

    public ReplicaState() {
        this.state = new ArrayList<>();
    }

    public void executeCommand(String commandJson) throws Exception {
        // Parse the command
        Command command = Command.fromJson(commandJson);
        
        // Verify client signature
        if (!verifyCommandSignature(command)) {
            throw new SecurityException("Invalid command signature from client " + command.getSenderId());
        }

        // TODO verify command type 

        // Execute based on type
        switch (command.getType()) {
            case APPEND -> append(command.getSenderId(), command.getContent());
            default -> throw new IllegalArgumentException("Unknown command type: " + command.getType());
        }
    }

    private boolean verifyCommandSignature(Command command) {
        try {
            String signableContent = command.getType() + ":" + command.getSenderId() + ":" + command.getContent();
            byte[] signableBytes = signableContent.getBytes();
            
            PublicKey clientPublicKey = Crypto.getClientPublicKey(command.getSenderId());
            return Crypto.verifySignature(signableBytes, command.getSignature(), clientPublicKey);
        } catch (Exception e) {
            System.err.println("Failed to verify command signature: " + e.getMessage());
            return false;
        }
    }

    public void append(long clientId, String content) {
        state.add(new AbstractMap.SimpleEntry<>(clientId, content));
    }

    public String getState() {
        return state.stream()
                .map(Map.Entry::getValue)
                .reduce("", String::concat);
    }
    
    public List<Map.Entry<Long, String>> getStateEntries() {
        return new ArrayList<>(state);
    }


}
