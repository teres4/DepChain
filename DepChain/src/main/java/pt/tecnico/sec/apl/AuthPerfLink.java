package pt.tecnico.sec.apl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;

import com.google.gson.Gson;

import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;
import pt.tecnico.sec.utils.MemberManagement;
import pt.tecnico.sec.utils.MessageType;

public class AuthPerfLink {
    public static final int BUFFER_SIZE = 65536; // Maximum UDP packet size

    private final DatagramSocket datagramSocket;
    private final ExecutorService executorService;
    private final SessionManager sessionManager;
    private final MemberManagement memberManagement;

    private final long ownId;
    private final PrivateKey privateKey;

    private final AtomicBoolean running = new AtomicBoolean(true);

    // Default constructor for production use
    public AuthPerfLink(long ownId, String privateKeyString) throws IOException, Exception {
        this(ownId, Crypto.getPrivateKey(privateKeyString), new DatagramSocket(8080), Executors.newFixedThreadPool(100),
                new SessionManager(), new MemberManagement(ownId));
    }

    // Constructor with dependency injection for testing
    public AuthPerfLink(long ownId, PrivateKey privateKey, DatagramSocket socket,
            ExecutorService executorService, SessionManager sessionManager,
            MemberManagement memberManagement) {
        this.ownId = ownId;
        this.privateKey = privateKey;
        this.datagramSocket = socket;
        this.executorService = executorService;
        this.sessionManager = sessionManager;
        this.memberManagement = memberManagement;
    }

    public void send(Message message, Session session) {
        DatagramPacket packet = message.toDatagramPacket(session.getAddr());

        boolean ackReceived = false;
        while (!ackReceived) {
            try {
                datagramSocket.send(packet);
                ackReceived = session.waitForReply();

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void receive() {
        while (running.get() && !datagramSocket.isClosed()) {
            byte[] buffer = new byte[BUFFER_SIZE]; 
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                datagramSocket.receive(packet);
                Message message = new Message(packet);
                System.out.println("Received: " + message);
                executorService.submit(() -> handlePacket(packet));
            } catch (IOException e) {
                if (running.get()) {
                    e.printStackTrace();
                }
                // If not running, socket was closed intentionally
            }
        }
    }

    public void shutdown() {
        running.set(false);
        datagramSocket.close();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void handlePacket(DatagramPacket packet) {

        // for each message, check if theres a session
        // if there is, verify the HMAC and the sequence number, and if they are
        // correct, process the message
        // if there isnt, check if its a connection request, and if it is, create a new
        // session and send a connection reply
        // if it isnt, discard the message

        InetSocketAddress addr = new InetSocketAddress(packet.getAddress(), packet.getPort());

        Message msg = new Message(packet);

        switch (msg.getType()) {
            case CONNECTION_REQUEST:
                // create new session and send connection reply
                handleConnectionRequest(msg, addr);
                break;

            case CONNECTION_REPLY:
                // verify the HMAC and the sequence number, and if they are correct, mark the
                // session as established
                handleConnectionReply(msg, addr);
                break;
            case ACK:
                // mark the message as acknowledged
                handleAck(msg, addr);
                break;
            case DATA:
                // verify the HMAC and the sequence number, and if they are correct, process the
                // message
                handleData(msg, addr);
                break;
            default:
                break;
        }
    }

    // connection request has message type, seq number 0, session key encrypted
    // with receiver public key, and digital signature using sender private key
    public void handleConnectionRequest(Message msg, InetSocketAddress addr) {
        try {
            // Verify message contents
            if (!validateConnectionRequest(msg, addr)) {
                return;
            }

            // Verify digital signature
            MemberInfo senderMember = memberManagement.getMemberByAddress(addr);
            if (senderMember == null) {
                System.err.println("Unknown sender address: " + addr);
                return;
            }

            PublicKey senderPublicKey = senderMember.getPublicKey();
            if (!Crypto.verifySignature(msg.getPayload(), msg.getSignature(), senderPublicKey)) {
                System.err.println("Failed to verify signature for connection request from: " + addr);
                return;
            }

            // Extract session key
            byte[] sessionKeyBytes = Crypto.decryptWithPrivateKey(msg.getEncryptedSessionKey(), this.privateKey);
            SecretKey sessionKey = Crypto.bytesToSecretKey(sessionKeyBytes);
            sessionManager.createSession(senderMember.getId(), addr, sessionKey);

            // Prepare reply and send it back to the sender
            // defini o payload do reply como "ACK" + senderID pq nsei o que por
            DatagramPacket replyPacket = prepareResponse(MessageType.CONNECTION_REPLY, "ACK" + senderMember.getId(),
                    msg.getSequenceNumber() + 1, sessionKey, addr);
            datagramSocket.send(replyPacket);

            System.out.println("Connection reply sent to: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle connection request: " + e.getMessage());
        }
    }

    // Validation helper methods for testing
    boolean validateConnectionRequest(Message msg, InetSocketAddress addr) {
        if (msg.getType() != MessageType.CONNECTION_REQUEST) {
            System.err.println("Invalid message type for connection request from: " + addr);
            return false;
        }
        if (msg.getSequenceNumber() != 0) {
            System.err.println("Invalid sequence number in connection request from: " + addr);
            return false;
        }
        return true;
    }

    // connection reply has message type, seq number 1, and HMAC and payload (ACK +
    // senderID)
    public void handleConnectionReply(Message msg, InetSocketAddress addr) {
        try {
            if (msg.getType() != MessageType.CONNECTION_REPLY) {
                System.err.println("Invalid message type for connection reply from: " + addr);
                return;
            }
            MemberInfo senderMember = memberManagement.getMemberByAddress(addr);
            if (senderMember == null) {
                System.err.println("Unknown sender address: " + addr);
                return;
            }

            Session session = sessionManager.getSession(senderMember.getId());
            if (session == null) {
                System.err
                        .println("No session found for sender ID: " + senderMember.getId() + " from address: " + addr);
                return;
            }

            if (!verifySequenceNumber(msg, session, addr)) {
                return;
            }

            if (!verifyHmac(msg, session, addr)) {
                return;
            }

            session.replyReceived();
            System.out.println("Connection reply verified and session established with: " + addr);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to handle connection reply: " + e.getMessage());
        }
    }

    boolean verifySequenceNumber(Message msg, Session session, InetSocketAddress addr) {
        if (msg.getSequenceNumber() != session.getSequenceNumber() + 1) {
            System.err.println("Invalid sequence number in message from: " + addr);
            return false;
        }
        return true;
    }

    boolean verifyHmac(Message msg, Session session, InetSocketAddress addr) {
        try {
            Message messageWithoutHmac = new Message(msg.getType(), msg.getPayload(), msg.getSequenceNumber());
            String jsonMessage = messageWithoutHmac.toJsonString();
            
            boolean isValid = Crypto.verifyHmac(jsonMessage, Base64.getEncoder().encodeToString(msg.getHmac()), session.getSessionKey());

            if (!isValid) {
                System.err.println("HMAC verification failed from: " + addr);
            }
            return isValid;
        } catch (Exception e) {
            System.err.println("HMAC verification error from: " + addr + " - " + e.getMessage());
            return false;
        }
    }

    public void sendAck(Session session) {
        try {
            MemberInfo senderMember = memberManagement.getMemberByAddress(session.getAddr());
            // defini o payload do reply como "ACK" + senderID pq nsei o que por no conteudo
            // do ACK
            DatagramPacket ackPacket = prepareResponse(MessageType.ACK, "ACK" + senderMember.getId(),
                    session.getSequenceNumber() + 1, session.getSessionKey(), session.getAddr());
            datagramSocket.send(ackPacket);
            System.out.println("ACK sent to: " + session.getAddr());
        } catch (IOException e) {
            System.err.println("Failed to send ACK: " + e.getMessage());
        }
    }

    public void handleAck(Message msg, InetSocketAddress addr) {
        try {
            MemberInfo senderMember = memberManagement.getMemberByAddress(addr);
            if (senderMember == null) {
                System.err.println("Unknown sender address: " + addr);
                return;
            }

            Session session = sessionManager.getSession(senderMember.getId());
            if (session == null) {
                System.err
                        .println("No session found for sender ID: " + senderMember.getId() + " from address: " + addr);
                return;
            }

            if (!verifySequenceNumber(msg, session, addr)) {
                return;
            }

            if (!verifyHmac(msg, session, addr)) {
                return;
            }

            session.replyReceived();
            System.out.println("Message acknowledged for session with sender: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle ACK: " + e.getMessage());
        }
    }

    public void sendData(Session session, String content) {
        try {
            DatagramPacket dataPacket = prepareResponse(MessageType.DATA, content, session.getSequenceNumber() + 1,
                    session.getSessionKey(), session.getAddr());
            datagramSocket.send(dataPacket);
            System.out.println("DATA sent to: " + session.getAddr());
        } catch (IOException e) {
            System.err.println("Failed to send DATA: " + e.getMessage());
        }
    }

    public void handleData(Message msg, InetSocketAddress addr) {
        try {
            MemberInfo senderMember = memberManagement.getMemberByAddress(addr);
            if (senderMember == null) {
                System.err.println("Unknown sender address: " + addr);
                return;
            }

            Session session = sessionManager.getSession(senderMember.getId());
            if (session == null) {
                System.err
                        .println("No session found for sender ID: " + senderMember.getId() + " from address: " + addr);
                return;
            }

            if (!verifySequenceNumber(msg, session, addr)) {
                return;
            }

            if (!verifyHmac(msg, session, addr)) {
                return;
            }

            // TODO: later this message that is received with the data will be used in the
            // consensus protocol
            System.out.println("Processing message from: " + addr);
            session.replyReceived();
        } catch (Exception e) {
            System.err.println("Failed to handle DATA: " + e.getMessage());
        }
    }

    public DatagramPacket prepareResponse(MessageType type, String payload, long sequenceNumber,
            SecretKey sessionKey, InetSocketAddress addr) {
        try {
            Message response = new Message(type, payload.getBytes(), sequenceNumber);
            String jsonMessage = response.toJsonString();
            byte[] hmac = Crypto.generateHMAC(sessionKey, jsonMessage.getBytes());
            response.setHmac(hmac);

            // Convert the message to a DatagramPacket
            return response.toDatagramPacket(addr);
        } catch (Exception e) {
            System.err.println("Failed to prepare response: " + e.getMessage());
            return null;
        }
    }

    // connection request has message type, seq number 0, encrypted session key and
    // digital signature of requester
    public void startConnection(long receiverID) {
        try {
            // generate session key and encrypt it with receiver public key
            MemberInfo receiverInfo = memberManagement.getMember(receiverID);
            if (receiverInfo == null) {
                System.err.println("No member found with ID: " + receiverID);
                return;
            }
            SecretKey sessionKey = Crypto.generateSecretKey();
            byte[] encryptedSessionKey = Crypto.encryptWithPublicKey(sessionKey.getEncoded(),
                    receiverInfo.getPublicKey());

            // Payload contains: sender ID + encrypted session key
            // its useless to send encrypted session key in the payload, but we need smth to
            // sign and verify in the connection request
            String payload = String.valueOf(ownId) + ":" + new String(encryptedSessionKey);
            byte[] payloadBytes = payload.getBytes();

            // send connection request with encrypted session key
            long sequenceNumber = 0;
            Message connectionRequest = new Message(MessageType.CONNECTION_REQUEST, payloadBytes, sequenceNumber, null);

            connectionRequest.setEncryptedSessionKey(new String(encryptedSessionKey));
            connectionRequest
                    .setSignature(Crypto.signMessage(new String(connectionRequest.getPayload()), this.privateKey));

            Session session = sessionManager.createSession(receiverID, receiverInfo.getAddr(), sessionKey);

            send(connectionRequest, session); // send connection request and wait for reply
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to start connection: " + e.getMessage());
        }
    }
    // Inner class to represent the connection message
    private static class ConnectionMessage {
        private final String encryptedSessionKey;
        private final String signature;

        public ConnectionMessage(String encryptedSessionKey, String signature) {
            this.encryptedSessionKey = encryptedSessionKey;
            this.signature = signature;
        }

        public String toJson() {
            Gson gson = new Gson();
            return gson.toJson(this);
        }
    }

    public MemberInfo[] getMembers() {
        return memberManagement.getMembers();
    }
}
