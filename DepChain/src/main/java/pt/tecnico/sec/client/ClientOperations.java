package pt.tecnico.sec.client;

import java.security.PrivateKey;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.apl.message.DataACKMessage;
import pt.tecnico.sec.apl.message.Message;
import pt.tecnico.sec.application.ApplicationMessageType;
import pt.tecnico.sec.application.DepChainMessage;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandType;
import pt.tecnico.sec.consensus.message.NewCommandMessage;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;

public class ClientOperations {
    private final AuthPerfLink authPerfLink;
    private final int clientId;
    private final int numReplicas;
    private final int f; // Fault tolerance parameter
    private long nonce = 0; // To prevent replay attacks

    public ClientOperations(int clientId, AuthPerfLink authPerfLink) {
        this.authPerfLink = authPerfLink;
        this.clientId = clientId;

        MemberInfo[] replicas = authPerfLink.getMembers();
        this.numReplicas = replicas.length;
        this.f = (this.numReplicas - 1) / 3;
    }

    public void append(String stringToAppend) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);

        this.nonce++;
        String signableContent = CommandType.APPEND + ":" + this.clientId + ":" + stringToAppend + ":" + this.nonce;
        String signature = Crypto.signMessage(signableContent, clientPrivKey);

        Command appendCommand = new Command(CommandType.APPEND, this.clientId, stringToAppend, signature, this.nonce);
        String commandJson = appendCommand.toJson();

        DepChainMessage appMsg = new DepChainMessage(ApplicationMessageType.CLIENT_COMMAND, commandJson);

        sendToAllReplicas(appMsg);
        waitForAcks(this.nonce);
    }

    private void sendToAllReplicas(DepChainMessage message) {
        String messageJson = message.toJson();
        MemberInfo[] replicas = authPerfLink.getMembers();
        for (MemberInfo replica : replicas) {
            try {
                authPerfLink.sendData(replica.getId(), messageJson.getBytes());
            } catch (Exception e) {
                System.err.println("Failed to send to replica " + replica.getId() + ": " + e.getMessage());
            }
        }
    }

    // waits for f+1 ACKs from replicas to ensure at least one honest replica
    // received the command
    private void waitForAcks(long nonce) throws Exception {
        int requiredAcks = this.f + 1;
        Set<Long> ackedReplicas = new HashSet<>();
        Queue<Message> incomingMessages = authPerfLink.getIncomingMessages();

        while (ackedReplicas.size() < requiredAcks) {
            Message msg = incomingMessages.poll();
            // TODO HOW DO I KNOW WHICH ACKS ARE FOR THIS COMMAND?
            // (maybe include nonce in the message and check it here)
            if (msg != null && msg instanceof DataACKMessage) {
                DataACKMessage ack = (DataACKMessage) msg;
                ackedReplicas.add(ack.getSenderId());
            }

            if (ackedReplicas.size() < requiredAcks) {
                Thread.sleep(100);
            }
        }

        if (ackedReplicas.size() >= requiredAcks) {
            System.out.println("Successfully received " + ackedReplicas.size() + " ACKs");
        } else {
            throw new Exception(
                    "Timeout: Only received " + ackedReplicas.size() + " ACKs out of " + requiredAcks + " required");
        }
    }
}