package pt.tecnico.sec.apl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;

import pt.tecnico.sec.apl.message.ConnectionACKMessage;
import pt.tecnico.sec.apl.message.ConnectionRequestMessage;
import pt.tecnico.sec.apl.message.DataACKMessage;
import pt.tecnico.sec.apl.message.DataMessage;
import pt.tecnico.sec.apl.message.Message;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.DHKeyAgreement;
import pt.tecnico.sec.utils.MemberInfo;
import pt.tecnico.sec.utils.MemberManager;

public class AuthPerfLink {

    // fields
    public interface MessageListener {
        void onMessage(String content);
    }

    public static final int BUFFER_SIZE = 65536; // Maximum UDP packet size

    private final long ownId;
    private final PrivateKey privateKey;
    private final boolean isReplica; 

    private final DatagramSocket datagramSocket;
    private final ExecutorService executorService;
    private final SessionManager sessionManager;
    private final MemberManager memberManagement;

    private final DHKeyAgreement dhKeyAgreement; // persistent DH key pair for this member

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final ConcurrentLinkedQueue<Message> incomingMessages = new ConcurrentLinkedQueue<>();

    private MessageListener consensusMessageListener; // separate listener for consensus messages to avoid contention
                                                      // with client commands
    private MessageListener messageListener; // general listener for all messages, can be used for client

    // constructors

    // Default constructor for production use
    public AuthPerfLink(long ownId, PrivateKey privateKey, int port) throws IOException, Exception {
        this(ownId, privateKey, true, port);
    }

    public AuthPerfLink(long ownId, PrivateKey privateKey, boolean isReplica, int port) throws IOException, Exception {
        this(ownId, privateKey, isReplica, new DatagramSocket(port), Executors.newFixedThreadPool(100),
                new SessionManager(), new MemberManager(ownId, isReplica), initializeDHKeyAgreement());
    }

    // Constructor with dependency injection for testing
    public AuthPerfLink(long ownId, PrivateKey privateKey, boolean isReplica, DatagramSocket socket,
            ExecutorService executorService, SessionManager sessionManager,
            MemberManager memberManagement, DHKeyAgreement dhKeyAgreement) {
        this.ownId = ownId;
        this.privateKey = privateKey;
        this.isReplica = isReplica;
        this.datagramSocket = socket;
        this.executorService = executorService;
        this.sessionManager = sessionManager;
        this.memberManagement = memberManagement;
        this.dhKeyAgreement = dhKeyAgreement;
    }

    private static DHKeyAgreement initializeDHKeyAgreement() throws Exception {
        DHKeyAgreement dh = new DHKeyAgreement();
        dh.initialize();
        return dh;
    }

    // public methods

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public void setConsensusMessageListener(MessageListener listener) {
        this.consensusMessageListener = listener;
    }

    public void receive() {
        while (running.get() && !datagramSocket.isClosed()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                datagramSocket.receive(packet);
                executorService.submit(() -> handlePacket(packet));
            } catch (IOException e) {
                if (running.get()) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void shutdown() {
        running.set(false);

        // Cleanup all sessions
        for (Session session : sessionManager.getAllSessions()) {
            session.cleanup();
        }

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

    public void sendData(long receiverID, byte[] content) {
        try {
            // sign the message with the session key and then send it
            Session session = sessionManager.getSession(receiverID);
            if (session == null) {
                session = startConnection(receiverID);
            }
            // Atomically get sequence number and advance to prevent race conditions
            long seqNum = session.getAndAdvanceSequenceNumber();
            System.out.println("\u001B[35mSENDING DATA with seqNum=" + seqNum + " to ID: " + receiverID
                    + " (nextSeqNum was " + seqNum + ", now " + (seqNum + 1) + ")\u001B[0m");
            DataMessage signedDataMessage = buildDataMessage(seqNum,
                    content,
                    session.getSessionKey());
            send(signedDataMessage, receiverID);
            System.out.println("DATA sent to ID: " + receiverID);
        } catch (Exception e) {
            System.err.println("Failed to send DATA: " + e.getMessage());
        }
    }

    public void startConnectionWithAllMembers() {
        MemberInfo[] members = memberManagement.getMembers();
        for (MemberInfo member : members) {
            if (member.getId() == this.ownId)
                continue; // skip self
            if (sessionManager.getSession(member.getId()) != null)
                continue; // already connected
            System.err.println("Attempting to connect to replica ID: " + member.getId() + " at address: " + member.getAddr());
            startConnection(member.getId());
        }
    }

    // this is for the clients
    public void connectToAllReplicas() {
        MemberInfo[] replicas = memberManagement.getMembers();
        System.err.println("Attempting to connect to all replicas. Found " + replicas.length + " members in config.");
        for (MemberInfo replica : replicas) {
            try {
                System.err.println("Attempting to connect to replica ID: " + replica.getId() + " at address: " + replica.getAddr());
                startConnection(replica.getId());
            } catch (Exception e) {
                System.err.println("Failed to connect to replica " + replica.getId() + ": " + e.getMessage());
            }
        }
    }

    public Session startConnection(long receiverID) {
        try {
            MemberInfo receiverInfo = memberManagement.getMember(receiverID);
            if (receiverInfo == null) {
                System.err.println("No member found with ID: " + receiverID);
                return null;
            }

            System.err.println("Starting connection to member ID: " + receiverID + " at address: " + receiverInfo.getAddr());
            byte[] publicKey = this.dhKeyAgreement.getEncodedPublicKey();

            // Sign the message
            String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey);
            String signature = Crypto.signMessage(this.ownId + ":" + publicKeyBase64, this.privateKey);
            
            // For clients, set clientID; for replicas, use default constructor (clientID = -1)
            ConnectionRequestMessage connectionRequest;
            if (isReplica) {
                connectionRequest = new ConnectionRequestMessage(this.ownId, publicKey, signature);
            } else {
                connectionRequest = new ConnectionRequestMessage(this.ownId, publicKey, signature, this.ownId);
            }

            Session session = sessionManager.getSession(receiverID);
            if (session == null) {
                session = sessionManager.createSession(receiverID, receiverInfo.getAddr(), null, false);
            }

            // Retry handshake with timeout
            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                System.out.println("Sending CONNECTION_REQUEST to " + receiverID + " (attempt " + attempt + "/" + maxRetries + ")");
                System.out.println("Message content: {" +
                    "clientID: " + connectionRequest.getClientID() + ", " +
                    "type: " + connectionRequest.getType() + ", " +
                    "senderId: " + connectionRequest.getSenderId() + "}");
                send(connectionRequest, receiverID);
                System.out.println(
                        "Sent CONNECTION_REQUEST to " + receiverID + " (attempt " + attempt + "/" + maxRetries + ")");

                // Wait for connection to be established
                if (session.waitForConnectionEstablished()) {
                    logger("Connection established with: " + receiverID);
                    return session;
                }

                System.out.println("Connection timeout with " + receiverID + ", retrying...");
            }

            System.err.println(
                    "Failed to establish connection with " + receiverID + " after " + maxRetries + " attempts");
            return null;
        } catch (Exception e) {
            System.err.println("Failed to start connection: " + e.getMessage());
            return null;
        }
    }

    // TODO: incomingMessage for layer above to consume, currently just prints
    // received messages
    public Queue<Message> getIncomingMessages() {
        return incomingMessages;
    }

    public MemberInfo[] getMembers() {
        return memberManagement.getMembers();
    }

    public MemberManager getMemberManager() {
        return memberManagement;
    }

    public String seeActiveSessions() {
        StringBuilder activeSessions = new StringBuilder("Active Sessions:\n");
        Collection<Session> sessions = sessionManager.getAllSessions();
        if (sessions.isEmpty())
            return "No active sessions found.";
        for (Session session : sessions) {
            activeSessions.append("Session with ID: ").append(session.getSenderID())
                    .append(", Address: ").append(session.getAddr())
                    .append(", Next Seq: ").append(session.getNextSequenceNumber())
                    .append(", Expected Seq: ").append(session.getExpectedSequenceNumber())
                    .append(", Window: ").append(session.getWindowSize())
                    .append(", Unacked: ").append(session.getUnacknowledgedPackets().size())
                    .append("\n");
        }
        return activeSessions.toString();
    }

    public Session getSession(long senderID) {
        return sessionManager.getSession(senderID);
    }

    // private methods

    private void handlePacket(DatagramPacket packet) {
        InetSocketAddress addr = new InetSocketAddress(packet.getAddress(), packet.getPort());

        try {
            Message msg = Message.fromPacket(packet);
            System.out.println("Message content: {" +
                    "type: " + msg.getType() + ", " +
                    "senderId: " + msg.getSenderId() + "}");
            switch (msg.getType()) {
                case CONNECTION_REQUEST -> handleConnectionRequest((ConnectionRequestMessage) msg, addr);
                case CONNECTION_ACK -> handleConnectionReply((ConnectionACKMessage) msg, addr);
                case DATA -> handleData((DataMessage) msg, addr);
                case ACK -> handleAck((DataACKMessage) msg, addr);
                default -> System.err.println("Unknown message type from: " + addr);
            }
        } catch (Exception e) {
            System.err.println("Failed to handle packet from: " + addr + " - " + e.getMessage());
        }
    }

    private void handleConnectionRequest(ConnectionRequestMessage msg, InetSocketAddress addr) {
        try {
            if (msg.getSequenceNumber() != 0) {
                System.err.println("Invalid sequence number in connection request from: " + addr);
                return;
            }

            MemberInfo senderMember = memberManagement.getMemberByAddress(addr);
            long senderId = msg.getSenderId();
            boolean isClient = false;
            if (senderMember == null) {
                // maybe a client
                if (msg.getClientID() != -1) {
                    System.out.println("Received connection request from client " + msg.getClientID() + " at " + addr);
                    senderId = msg.getClientID();
                    isClient = true;
                } else {
                    System.err.println("Unknown sender address for connection request: " + addr);
                    return;
                }
            }

            // Check if session already exists
            Session existingSession = sessionManager.getSession(senderId);
            if (existingSession != null) {
                // Duplicate request - resend ACK (original ACK was probably lost)
                System.out.println(
                        "Received duplicate CONNECTION_REQUEST from " + senderId + ", resending ACK");

                // Recreate and resend the ACK
                byte[] responderDHPublicKey = this.dhKeyAgreement.getEncodedPublicKey();
                String responderKeyBase64 = Base64.getEncoder().encodeToString(responderDHPublicKey);
                String signature = Crypto.signMessage(this.ownId + ":" + responderKeyBase64, this.privateKey);

                ConnectionACKMessage reply = new ConnectionACKMessage(1, responderDHPublicKey, this.ownId, signature);
                datagramSocket.send(reply.toDatagramPacket(addr));
                return;
            }

            PublicKey senderPublicKey;
            if (!isClient) {
                senderPublicKey = senderMember.getPublicSignatureKey();
            } else {
                senderPublicKey = Crypto.getClientPublicKey(senderId);
            }
            if (!Crypto.verifySignature(msg.getSignableContent().getBytes(), msg.getSignature(), senderPublicKey)) {
                System.err.println("Failed to verify signature for connection request from: " + addr);
                return;
            }

            // Get the DH public key from the initiator
            byte[] initiatorDHPublicKey = msg.getDhPublicKey();

            // compute shared secret
            byte[] responderDHPublicKey = this.dhKeyAgreement.getEncodedPublicKey();
            SecretKey sessionKey = this.dhKeyAgreement.computeSharedSecret(initiatorDHPublicKey);

            String responderKeyBase64 = Base64.getEncoder().encodeToString(responderDHPublicKey);
            String signature = Crypto.signMessage(this.ownId + ":" + responderKeyBase64, this.privateKey);

            Session session = sessionManager.createSession(senderId, addr, sessionKey, isClient);

            // Handshake: reply with seq 1 (request was seq 0)
            long replySeqNum = 1;
            ConnectionACKMessage reply = new ConnectionACKMessage(replySeqNum, responderDHPublicKey,
                    this.ownId, signature);

            // Send reply directly (no buffering for handshake)
            datagramSocket.send(reply.toDatagramPacket(addr));

            // After handshake, initialize session for data transfer:
            // - Both sender and receiver start at seq 2 for first data packet
            // - Both expect seq 2 as well
            session.setSequenceNumber(1); // Sets sendBase=1, nextSeqNum=2, expectedSeqNum=2

            // Mark session as established after sending reply
            session.markConnectionEstablished();

            System.out.println("Connection reply sent to: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle connection request: " + e.getMessage());
        }
    }

    private void handleConnectionReply(ConnectionACKMessage msg, InetSocketAddress addr) {
        try {
            Session session = getSessionForAddress(addr);
            if (session == null)
                return;

            // Verify it's the expected handshake ACK (seq 1)
            if (msg.getSequenceNumber() != 1) {
                System.err.println("Invalid connection reply sequence from: " + addr);
                return;
            }

            MemberInfo senderMember = memberManagement.getMemberByAddress(addr);
            if (senderMember == null) {
                System.err.println("Unknown sender address for connection reply: " + addr);
                return;
            }

            PublicKey senderPublicKey = senderMember.getPublicSignatureKey();
            if (!Crypto.verifySignature(msg.getSignableContent().getBytes(), msg.getSignature(), senderPublicKey)) {
                System.err.println("Failed to verify signature for connection request from: " + addr);
                return;
            }

            // Get the responder's DH public key from the ACK message
            byte[] responderDHPublicKey = msg.getDHPublicKey();

            // Compute shared secret
            SecretKey sessionKey = this.dhKeyAgreement.computeSharedSecret(responderDHPublicKey);
            session.setSessionKey(sessionKey);

            // After handshake, initialize session for data transfer:
            // - Sender starts at seq 2 for first data packet
            // - Receiver expects seq 2 as well
            session.setSequenceNumber(1); // Sets sendBase=1, nextSeqNum=2, expectedSeqNum=2

            // Mark session as established
            session.markConnectionEstablished();

            System.out.println("Connection reply verified and session established with: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle connection reply: " + e.getMessage());
        }
    }

    public void handleData(DataMessage msg, InetSocketAddress addr) {
        try {
            Session session = getSessionForAddress(addr);
            if (session == null) {
                long senderId = msg.getSenderId();
                session = sessionManager.getSession(senderId);

                if (session == null) {
                    System.err.println("Unknown sender address or client ID: " + addr + " (senderId: " + senderId + ")");
                    return;
                }
            }

            long expectedSeq = session.getExpectedSequenceNumber();

            // go back n only accept packets in order
            if (msg.getSequenceNumber() == expectedSeq) { // accept only expected sequence number
                String jsonWithoutHmac = msg.withoutHmac().toJsonString();
                if (!verifyHmac(jsonWithoutHmac, msg.getHmac(), session, addr))
                    return;

                // check if sender id is really the one it claims to be
                try {
                    String contentStr = new String(msg.getContent());
                    // search for senderId in content and verify it matches session senderId
                    if (contentStr.contains("senderId")) {
                        String[] parts = contentStr.split("senderId\":");
                        if (parts.length > 1) {
                            String senderIdStr = parts[1].split("[,}]")[0].trim();
                            long senderId = Long.parseLong(senderIdStr);
                            if (senderId != session.getSenderID()) {
                                System.err
                                        .println("Sender ID in content does not match session sender ID for: " + addr);
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to verify sender ID in content for: " + addr + " - " + e.getMessage());
                    return;
                }
                String contentStr = new String(msg.getContent());
                if (session.isClientSession()) {
                    messageListener.onMessage(contentStr);
                } else {
                    consensusMessageListener.onMessage(contentStr);
                }

                // Update expected sequence number and send cumulative ACK
                session.setExpectedSequenceNumber(expectedSeq + 1);
                String contentPreview = contentStr.length() > 50 ? contentStr.substring(0, 50) + "..." : contentStr;
                System.out.println("Received expected packet " + msg.getSequenceNumber() + " from: " + addr + " (content: " + contentPreview + ") sending ACK for seq " + (expectedSeq + 1));

                sendDataAck(session, expectedSeq + 1);

            } else {
                // Out-of-order packet - discard and resend ACK for last in-order packet
                System.out.println("Received out-of-order packet " + msg.getSequenceNumber() +
                        " (expected " + expectedSeq + ") from: " + addr + ", discarding");

                // Send cumulative ACK for last correctly received packet
                if (expectedSeq > 0) {
                    sendDataAck(session, expectedSeq);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to handle DATA: " + e.getMessage());
        }
    }

    public void handleAck(DataACKMessage msg, InetSocketAddress addr) {
        try {
            Session session = getSessionForAddress(addr);
            if (session == null)
                return;

            if (!verifyHmac(msg.withoutHmac().toJsonString(), msg.getHmac(), session, addr))
                return;

            // Cumulative ACK: acknowledges all packets up to seqNum - 1
            long ackNum = msg.getSequenceNumber();
            session.handleAck(ackNum);
            System.out.println("Cumulative ACK " + ackNum + " received from: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle ACK: " + e.getMessage());
        }
    }

    // send helpers
    private void send(Message message, long id) throws Exception {
        executorService.submit(() -> sendPacket(message, id));
    }

    private void sendPacket(Message message, long id) {
        Session session = sessionManager.getSession(id);
        if (session == null) {
            session = startConnection(id);
        }

        // Go-Back-N: buffer data messages for retransmission
        if (message instanceof DataMessage) {
            DataMessage dataMsg = (DataMessage) message;

            // Wait if window is full
            while (!session.canSend()) {
                try {
                    Thread.sleep(5); // brief wait before checking again
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Buffer for potential retransmission BEFORE sending
            // This prevents race condition where ACK arrives before buffering
            try {
                final Session finalSession = session;
                session.bufferPacket(dataMsg.getSequenceNumber(), dataMsg, () -> handleTimeout(finalSession));

                // Send the packet after buffering
                DatagramPacket packet = message.toDatagramPacket(session.getAddr());
                datagramSocket.send(packet);

                System.out.println("Sent packet " + dataMsg.getSequenceNumber() + " to: " + session.getAddr());
            } catch (IOException e) {
                System.err.println("Failed to send message: " + e.getMessage());
            }
        } else {
            // Handshake and other messages - send immediately without buffering
            // Use waitForConnectionEstablished() to handle handshake timeouts
            try {
                DatagramPacket packet = message.toDatagramPacket(session.getAddr());
                datagramSocket.send(packet);
                System.out.println("Sent message seq " + message.getSequenceNumber() + " to: " + session.getAddr());
            } catch (IOException e) {
                System.err.println("Failed to send message: " + e.getMessage());
            }
        }
    }

    private void handleTimeout(Session session) {
        System.out.println("Timeout! Retransmitting all unacknowledged packets to: " + session.getSenderID());

        // Retransmit all packets in the window
        Map<Long, DataMessage> unacked = session.getUnacknowledgedPackets();
        for (DataMessage msg : unacked.values()) {
            try {
                DatagramPacket packet = msg.toDatagramPacket(session.getAddr());
                datagramSocket.send(packet);
                System.out.println("Retransmitted packet " + msg.getSequenceNumber());
            } catch (IOException e) {
                System.err.println("Failed to retransmit packet: " + e.getMessage());
            }
        }

        // Restart timer for the retransmitted packets
        // The timer handler is preserved from when the window was first created
        session.restartTimerIfNeeded();
    }

    private void sendDataAck(Session session, long seqNum) {
        try {
            DataACKMessage ack = buildDataAck(seqNum, session.getSessionKey());
            datagramSocket.send(ack.toDatagramPacket(session.getAddr()));
            System.out.println("ACK " + seqNum + " sent to: " + session.getAddr());
        } catch (Exception e) {
            System.err.println("Failed to send ACK: " + e.getMessage());
        }
    }

    // build message helpers

    private DataMessage buildDataMessage(long seqNum, byte[] content, SecretKey sessionKey) throws Exception {
        DataMessage msg = new DataMessage(seqNum, content, null, this.ownId);
        return new DataMessage(seqNum, content, Crypto.generateHMAC(sessionKey, msg.toJsonString().getBytes()),
                this.ownId);
    }

    private DataACKMessage buildDataAck(long seqNum, SecretKey sessionKey) throws Exception {
        DataACKMessage msgWithoutHmac = new DataACKMessage(seqNum, null, this.ownId);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toJsonString().getBytes());
        return new DataACKMessage(seqNum, hmac, this.ownId);
    }

    // verification helpers

    boolean verifySequenceNumber(Message msg, Session session, InetSocketAddress addr) {
        if (msg.getSequenceNumber() != session.getSequenceNumber() + 1) {
            System.err.println("Invalid sequence number in message from: " + addr +
                    " (expected " + (session.getSequenceNumber() + 1) + ", got " + msg.getSequenceNumber() + ")");
            return false;
        }
        return true;
    }

    boolean verifyHmac(String jsonWithoutHmac, byte[] hmac, Session session, InetSocketAddress addr) {
        try {
            boolean isValid = Crypto.verifyHmac(jsonWithoutHmac,
                    Base64.getEncoder().encodeToString(hmac), session.getSessionKey());
            if (!isValid)
                System.err.println("HMAC verification failed from: " + addr);
            return isValid;
        } catch (Exception e) {
            System.err.println("HMAC verification error from: " + addr + " - " + e.getMessage());
            return false;
        }
    }

    private Session getSessionForAddress(InetSocketAddress addr) {
        MemberInfo senderMember = memberManagement.getMemberByAddress(addr);
        if (senderMember == null) {
            System.err.println("Unknown sender address: " + addr);
            return null;
        }
        Session session = sessionManager.getSession(senderMember.getId());
        if (session == null) {
            System.err.println("No session found for sender ID: " + senderMember.getId());
            return null;
        }
        return session;
    }

    private void logger(String message) {
        String red = "\u001B[31m";
        String reset = "\u001B[0m";
        System.out.println(red + "[AuthPerfLink] " + message + reset);
    }
}
