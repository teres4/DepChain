package pt.tecnico.sec.application;

import java.util.concurrent.*;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.consensus.Consensus;
import pt.tecnico.sec.consensus.message.HotStuffMessage;
import pt.tecnico.sec.replica.Replica;

/**
 * It receives all messages via APL and routes them to the appropriate handler:
 * - CONSENSUS messages → Consensus
 * - CLIENT_COMMAND messages → Replica
 */
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
                DepChainMessage appMsg = DepChainMessage.fromBytes(content);
                switch (appMsg.getMessageType()) {
                    case CONSENSUS:
                        HotStuffMessage consensusMsg = HotStuffMessage.fromJson(appMsg.getPayload());
                        executorService.submit(() -> routeToConsensus(consensusMsg));
                        break;
                    case CLIENT_COMMAND:
                        Command clientCmd = Command.fromJson(appMsg.getPayload());
                        executorService.submit(() -> routeToReplica(clientCmd));
                        break;
                    default:
                        System.err.println("[ROUTER] Unknown message type: " + appMsg.getMessageType());
                }
            } catch (Exception e) {
                System.err.println("[ROUTER] Failed to handle message: " + e.getMessage());
            }
        });
    }

    private void routeToConsensus(HotStuffMessage msg) {
        try {
            consensus.handleMessage(msg);
        } catch (Exception e) {
            System.err.println("[ROUTER] Error routing consensus message: " + e.getMessage());
        }
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
        DepChainMessage appMsg = new DepChainMessage(ApplicationMessageType.CONSENSUS, message.toJson());
        try {
            executorService.submit(() -> authPerfLink.sendData(targetReplicaId, appMsg.toBytes()));
        } catch (Exception e) {
            System.err.println("[ROUTER] Failed to send consensus message: " + e.getMessage());
        }
    }

}
