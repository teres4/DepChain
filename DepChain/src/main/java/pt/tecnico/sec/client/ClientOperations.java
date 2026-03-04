package pt.tecnico.sec.client;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.apl.message.DataACKMessage;
import pt.tecnico.sec.apl.message.DataMessage;
import pt.tecnico.sec.apl.message.Message;
import pt.tecnico.sec.utils.MemberInfo;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public class ClientOperations {
    private AuthPerfLink authPerfLink;
    private int clientId;

    private int numReplicas;
    private int f; // Fault tolerance parameter

    public ClientOperations(int clientId, AuthPerfLink authPerfLink) {
        this.authPerfLink = authPerfLink;
        this.clientId = clientId;

        MemberInfo[] replicas = authPerfLink.getMembers();
        this.numReplicas = replicas.length;
        
        this.f = (this.numReplicas - 1) / 3;
    }

    public String append(String stringToAppend) throws Exception {
        byte[] messageBytes = stringToAppend.getBytes();
        
        sendToAllReplicas(messageBytes);
        
        // Wait for f+1 ACKs
        waitForAcks();
    }

    public void sendToAllReplicas(byte[] messageBytes) {
        MemberInfo[] replicas = authPerfLink.getMembers();
        for (MemberInfo replica : replicas) {
            try {
                // TODO this method sets sequence number as -1. thats not ok
                DataMessage dataMsg = new DataMessage(messageBytes);
                authPerfLink.sendData(replica.getId(), dataMsg);
                System.out.println("Sent to replica " + replica.getId());
            } catch (Exception e) {
                System.err.println("Failed to send to replica " + replica.getId() + ": " + e.getMessage());
            }
        }
    }

    public void waitForAcks() {
        int requiredAcks = this.f + 1;

        Set<Long> ackedReplicas = new HashSet<>();

        // TODO track which replica sent the ACK
        Queue<Message> incomingMessages = authPerfLink.getIncomingMessages();

        while (ackedReplicas.size() < requiredAcks) {
            if (msg instanceof DataACKMessage) {
                DataACKMessage ack = (DataACKMessage) msg;
                ackedReplicas.add(ack.getSenderId()); 
            }
            
            if (ackedReplicas.size() < requiredAcks) {
                Thread.sleep(100); // Brief wait before checking again
            }
        }
        
        if (ackedReplicas.size() >= requiredAcks) {
            System.out.println("Successfully received " + ackedReplicas.size() + " ACKs");
            return "Success";
        } else {
            throw new Exception("Timeout: Only received " + ackedReplicas.size() + " ACKs out of " + requiredAcks + " required");
        }
    }
}