package pt.tecnico.sec.replica;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import pt.tecnico.sec.client.message.Command;

public class ReplicaState {

    private final List<Map.Entry<Long, String>> state;

    public ReplicaState() {
        this.state = new ArrayList<>();
    }

    // execute command that has been validated and decided by consensus
    public boolean executeCommand(String commandJson) throws Exception {
        try {
            // Parse the command
            Command command = Command.fromJson(commandJson);
            
            // Execute based on type
            switch (command.getType()) {
                case APPEND -> {
                    append(command.getSenderId(), command.getContent());
                    return true;
                }
            }
        } catch (Exception e) {
            throw new Exception("Execution error: " + e.getMessage());
        }
        return false;
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
