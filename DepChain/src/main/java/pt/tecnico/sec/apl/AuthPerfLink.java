package pt.tecnico.sec.apl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collection;
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

    private final DatagramSocket datagramSocket;
    private final ExecutorService executorService;
    private final SessionManager sessionManager;
    private final MemberManager memberManagement;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final ConcurrentLinkedQueue<Message> incomingMessages = new ConcurrentLinkedQueue<>();

    private MessageListener messageListener;

    // constructors

    // Default constructor for production use
    public AuthPerfLink(long ownId, String privateKeyString) throws IOException, Exception {
        this(ownId, Crypto.getPrivateKey(privateKeyString), new DatagramSocket(8080), Executors.newFixedThreadPool(100),
                new SessionManager(), new MemberManager(ownId));
    }

    // Constructor with dependency injection for testing
    public AuthPerfLink(long ownId, PrivateKey privateKey, DatagramSocket socket,
            ExecutorService executorService, SessionManager sessionManager,
            MemberManager memberManagement) {
        this.ownId = ownId;
        this.privateKey = privateKey;
        this.datagramSocket = socket;
        this.executorService = executorService;
        this.sessionManager = sessionManager;
        this.memberManagement = memberManagement;
    }

    // public methods

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
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

    public void sendData(Session session, String content) {
        try {
            long seqNum = session.getSequenceNumber() + 1;
            DataMessage msg = buildDataMessage(seqNum, content.getBytes(), session.getSessionKey());
            session.setSequenceNumber(seqNum);
            send(msg, session.getSenderID());
            System.out.println("DATA sent to: " + session.getAddr());
        } catch (Exception e) {
            System.err.println("Failed to send DATA: " + e.getMessage());
        }
    }

    public void sendData(long receiverID, DataMessage DataMessage) {
        try {
            // sign the message with the session key and then send it
            Session session = sessionManager.getSession(receiverID);
            if (session == null) {
                session = startConnection(receiverID);
            }
            DataMessage signedDataMessage = buildDataMessage(session.getSequenceNumber() + 1, DataMessage.getContent(),
                    session.getSessionKey());
            send(signedDataMessage, receiverID);
            System.out.println("DATA sent to ID: " + receiverID);
        } catch (Exception e) {
            System.err.println("Failed to send DATA: " + e.getMessage());
        }
    }

    public Session startConnection(long receiverID) {
        try {
            MemberInfo receiverInfo = memberManagement.getMember(receiverID);
            if (receiverInfo == null) {
                System.err.println("No member found with ID: " + receiverID);
                return null;
            }

            SecretKey sessionKey = Crypto.generateSecretKey();
            byte[] encryptedSessionKey = Crypto.encryptWithPublicKey(sessionKey.getEncoded(),
                    receiverInfo.getPublicKey());
            String encryptedSessionKeyBase64 = Base64.getEncoder().encodeToString(encryptedSessionKey);

            String signature = Crypto.signMessage(ownId + ":" + encryptedSessionKeyBase64, this.privateKey);
            ConnectionRequestMessage connectionRequest = new ConnectionRequestMessage(ownId, encryptedSessionKeyBase64,
                    signature);

            Session session = sessionManager.createSession(receiverID, receiverInfo.getAddr(), sessionKey);
            send(connectionRequest, receiverID);
            return session;
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
                    .append(", Sequence Number: ").append(session.getSequenceNumber())
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
            System.out.println("Received: " + msg);

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
            if (senderMember == null) {
                System.err.println("Unknown sender address: " + addr);
                return;
            }

            PublicKey senderPublicKey = senderMember.getPublicKey();
            if (!Crypto.verifySignature(msg.getSignableContent().getBytes(), msg.getSignature(), senderPublicKey)) {
                System.err.println("Failed to verify signature for connection request from: " + addr);
                return;
            }

            byte[] sessionKeyBytes = Crypto.decryptWithPrivateKey(msg.getEncryptedSessionKey(), this.privateKey);
            SecretKey sessionKey = Crypto.bytesToSecretKey(sessionKeyBytes);
            Session session = sessionManager.createSession(senderMember.getId(), addr, sessionKey);

            long replySeqNum = msg.getSequenceNumber() + 1;
            ConnectionACKMessage reply = buildConnectionReply(replySeqNum, sessionKey);
            session.setSequenceNumber(replySeqNum);
            datagramSocket.send(reply.toDatagramPacket(addr));

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

            // if it's a retransmission, ignore (connection already established)
            if (msg.getSequenceNumber() == session.getSequenceNumber()) {
                System.out.println("Received duplicate connection reply from: " + addr + ", ignoring");
                return;
            }

            if (!verifySequenceNumber(msg, session, addr))
                return;
            if (!verifyHmac(msg.withoutHmac().toJsonString(), msg.getHmac(), session, addr))
                return;

            session.setSequenceNumber(msg.getSequenceNumber());
            session.replyReceived();
            System.out.println("Connection reply verified and session established with: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle connection reply: " + e.getMessage());
        }
    }

    public void handleData(DataMessage msg, InetSocketAddress addr) {
        try {
            Session session = getSessionForAddress(addr);
            if (session == null)
                return;

            // if it's a retransmission, resend ACK
            if (msg.getSequenceNumber() == session.getSequenceNumber()) {
                System.out.println("Received retransmission of data from: " + addr + ", resending ACK");
                sendDataAck(session, msg.getSequenceNumber() + 1);
                return;
            }

            if (!verifySequenceNumber(msg, session, addr))
                return;
            String jsonWithoutHmac = msg.withoutHmac().toJsonString();
            if (!verifyHmac(jsonWithoutHmac, msg.getHmac(), session, addr))
                return;

            // this data will later be used by consensus layer, for now we just print it
            session.setSequenceNumber(msg.getSequenceNumber());
            System.out.println("Received DATA from: " + addr + " - Content: " + new String(msg.getContent()));

            if (messageListener != null) {
                messageListener.onMessage(new String(msg.getContent()));
            }

            sendDataAck(session, msg.getSequenceNumber() + 1);
        } catch (Exception e) {
            System.err.println("Failed to handle DATA: " + e.getMessage());
        }
    }

    public void handleAck(DataACKMessage msg, InetSocketAddress addr) {
        try {
            Session session = getSessionForAddress(addr);
            if (session == null)
                return;

            if (!verifySequenceNumber(msg, session, addr))
                return;
            if (!verifyHmac(msg.withoutHmac().toJsonString(), msg.getHmac(), session, addr))
                return;

            session.setSequenceNumber(msg.getSequenceNumber());
            session.replyReceived();
            System.out.println("Message acknowledged by: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle ACK: " + e.getMessage());
        }
    }

    // send helpers

    private void send(Message message, long id) throws Exception {
        Session session = sessionManager.getSession(id);
        if (session == null) {
            session = startConnection(id);
        }
        DatagramPacket packet = message.toDatagramPacket(session.getAddr());

        boolean ackReceived = false;
        while (!ackReceived) {
            try {
                datagramSocket.send(packet);
                ackReceived = session.waitForReply();
            } catch (IOException e) {
                System.err.println("Failed to send message: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for ACK: " + e.getMessage());
            }
        }
    }

    private void sendDataAck(Session session, long seqNum) {
        try {
            DataACKMessage ack = buildDataAck(seqNum, session.getSessionKey());
            session.setSequenceNumber(seqNum);
            datagramSocket.send(ack.toDatagramPacket(session.getAddr()));
            System.out.println("ACK sent to: " + session.getAddr());
        } catch (Exception e) {
            System.err.println("Failed to send ACK: " + e.getMessage());
        }
    }

    // build message helpers

    private ConnectionACKMessage buildConnectionReply(long seqNum, SecretKey sessionKey) throws Exception {
        ConnectionACKMessage msgWithoutHmac = new ConnectionACKMessage(seqNum, null);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toJsonString().getBytes());
        return new ConnectionACKMessage(seqNum, hmac);
    }

    private DataMessage buildDataMessage(long seqNum, byte[] content, SecretKey sessionKey) throws Exception {
        DataMessage msg = new DataMessage(seqNum, content, null);
        return new DataMessage(seqNum, content, Crypto.generateHMAC(sessionKey, msg.toJsonString().getBytes()));
    }

    private DataACKMessage buildDataAck(long seqNum, SecretKey sessionKey) throws Exception {
        DataACKMessage msgWithoutHmac = new DataACKMessage(seqNum, null);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toJsonString().getBytes());
        return new DataACKMessage(seqNum, hmac);
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
}