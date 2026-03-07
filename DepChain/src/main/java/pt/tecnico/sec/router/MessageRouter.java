package pt.tecnico.sec.router;

import java.util.concurrent.*;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.consensus.Consensus;
import pt.tecnico.sec.consensus.message.HotStuffMessage;
import pt.tecnico.sec.replica.Replica;

// receives all messages and routes them to right handles
public class MessageRouter {
    private AuthPerfLink authPerfLink;
    private Consensus consensus;
    private Replica replica;
    private ExecutorService executorService = Executors.newFixedThreadPool(100);

    public MessageRouter(AuthPerfLink authPerfLink, Consensus consensus, Replica replica) {
        this.authPerfLink = authPerfLink;
        this.consensus = consensus;
        this.replica = replica;
    }

    public void mainLoop() {
        executorService.submit(() -> authPerfLink.receive());
        authPerfLink.setMessageListener((content) -> {
            try {
                Command cmd = Command.fromJson(content);
                routeToReplica(cmd);
            } catch (Exception e) {
                System.err.println("Failed to parse client command: " + e.getMessage());
                e.printStackTrace();
            }
        });
        authPerfLink.setConsensusMessageListener((content) -> {
            try {
                HotStuffMessage msg = HotStuffMessage.fromJson(content);
                routeToConsensus(msg);
            } catch (Exception e) {
                System.err.println("Failed to parse consensus message: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void routeToConsensus(HotStuffMessage msg) {
        executorService.submit(() -> {
            try {
                consensus.handleMessage(msg);
            } catch (Exception e) {
                System.err.println("[ROUTER] Error routing consensus message: " + e.getMessage());
            }
        });
    }

    private void routeToReplica(Command command) {
        replica.handleClientCommand(command);
    }

    public String getCommandtoPropose() {
        return replica.getCommandToPropose();
    }

    public void commandDecided(String commandJson) {
        replica.commandDecided(commandJson);
    }

    public void sendConsensusMessage(HotStuffMessage message, long targetReplicaId) {
        try {
            executorService.submit(() -> authPerfLink.sendData(targetReplicaId, message.toJson().getBytes()));
        } catch (Exception e) {
            System.err.println("[ROUTER] Failed to send consensus message: " + e.getMessage());
        }
    }

}
