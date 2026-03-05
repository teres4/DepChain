package pt.tecnico.sec.application;

import java.lang.reflect.Array;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.consensus.Consensus;
import pt.tecnico.sec.consensus.message.HotStuffMessage;

import pt.tecnico.sec.utils.*;

public class Application {

    private AuthPerfLink authPerfLink;

    private Consensus consensus;

    private ArrayList<String> messageLog = new ArrayList<>(); // array of apend only strings
    private ConcurrentLinkedQueue<String> pendingCommands = new ConcurrentLinkedQueue<>(); // queue of client commands
                                                                                           // waiting to be executed
    private ConcurrentHashMap<Long, Long> clientNonces = new ConcurrentHashMap<>(); // track latest nonce for each
                                                                                    // client to prevent replay

    private ExecutorService executorService = java.util.concurrent.Executors.newFixedThreadPool(100);

    public Application(AuthPerfLink authPerfLink, Consensus consensus) {
        this.authPerfLink = authPerfLink;
        this.consensus = consensus;
    }

    public void sendConsensusMessage(HotStuffMessage message, long id) {
        System.out.println("\u001B[34mSending consensus message " + message.getHotStuffType() + " to replica " + id
                + "\u001B[0m");
        DepChainMessage appMsg = new DepChainMessage(ApplicationMessageType.CONSENSUS, message.toJson());
        try {
            executorService.submit(() -> authPerfLink.sendData(id, appMsg.toBytes()));
        } catch (Exception e) {
            System.err.println("Failed to send consensus message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void mainLoop() {
        executorService.submit(() -> authPerfLink.receive());
        authPerfLink.setMessageListener((content) -> {
            try {
                DepChainMessage appMsg = DepChainMessage.fromBytes(content);
                switch (appMsg.getMessageType()) {
                    case CONSENSUS:
                        HotStuffMessage consensusMsg = HotStuffMessage.fromJson(appMsg.getPayload());
                        // Handle consensus messages synchronously to preserve order
                        executorService.submit(() -> handleConsensusMessage(consensusMsg));
                        break;
                    case CLIENT_COMMAND:
                        Command clientCmd = Command.fromJson(appMsg.getPayload());
                        // Client commands can be handled asynchronously
                        executorService.submit(() -> handleClientCommand(clientCmd));
                        break;
                    default:
                        System.err.println("Unknown message type: " + appMsg.getMessageType());
                }
            } catch (Exception e) {
                System.err.println("Failed to handle application message: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void handleConsensusMessage(HotStuffMessage msg) {
        try {
            System.out
                    .println("\u001B[34mReceived consensus message of type " + msg.getHotStuffType() + " from replica "
                            + msg.getSenderId() + "\u001B[0m");
            consensus.handleMessage(msg);

        } catch (Exception e) {
            System.err.println("Error handling consensus message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleClientCommand(Command command) {
        Long clientID = command.getSenderId();
        Long nonce = command.getNonce();
        // Check for replay attack
        if (clientNonces.containsKey(clientID) && nonce <= clientNonces.get(clientID)) {
            System.out.println("Rejected replayed command from client " + clientID + " with nonce " + nonce);
            return;
        } else {
            clientNonces.put(clientID, nonce); // Update latest nonce for this client
        }

        // check signature
        if (!checkCommandSignature(command)) {
            System.out.println("Rejected command with invalid signature from client " + clientID);
            return;
        }

        // add to pending commands of consensus
        pendingCommands.add(command.toJson());
    }

    /**
     * get a pending command from queue to propose in the next consensus round.
     * This is called by consensus when it needs a command to propose in a new node.
     * 
     * @return
     */
    public String getCommandtoPropose() {
        return pendingCommands.peek();
    }

    public void commandDecided(String command) {
        // TODO: validar comando outra vez (copiar da handleClientCommand) antes de
        // executar
        // delete from pending
        pendingCommands.remove(command);
        try {
            Command clientCmd = Command.fromJson(command);
            // TODO: respond to client
        } catch (Exception e) {
            System.out.println("Failed to handle client command " + command + ": " + e.getMessage());
        }
    }

    public boolean checkCommandFormat(String commandJson) {
        try {
            Command cmd = Command.fromJson(commandJson);
            if (cmd.getType() == null || cmd.getSenderId() <= 0 || cmd.getNonce() < 0 || !checkCommandSignature(cmd)) {
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public boolean checkCommandSignature(Command cmd) {
        String contentToVerify = cmd.getType().toString() + cmd.getContent() + cmd.getSenderId() + cmd.getNonce();
        // String signableContent = CommandType.APPEND + ":" + this.clientId + ":" +
        // stringToAppend + ":" + this.nonce;
        try {
            PublicKey clientPubKey = Crypto.getClientPublicKey(cmd.getSenderId());
            return Crypto.verifySignature(contentToVerify.getBytes(), cmd.getSignature(), clientPubKey);
        } catch (Exception e) {
            System.err.println("Failed to verify command signature: " + e.getMessage());
            return false;
        }
    }

}