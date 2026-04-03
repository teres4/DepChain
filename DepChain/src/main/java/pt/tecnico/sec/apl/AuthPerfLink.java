package pt.tecnico.sec.apl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    public interface ConsensusMessageListener {
        void onConsensusMessage(String content);
    }

    public static final int BUFFER_SIZE = 65536; // Maximum UDP packet size
    public static final int FRAGMENT_SIZE = 60000; // Size of each fragment

    private final long ownId;
    private final PrivateKey privateKey;
    private final boolean isReplica;

    private final DatagramSocket datagramSocket;
    private final ExecutorService executorService;
    private final ExecutorService sendExecutorService;
    private final SessionManager sessionManager;
    private final MemberManager memberManagement;

    private final AtomicBoolean running = new AtomicBoolean(true);
 
    private final AtomicLong messageIdCounter = new AtomicLong(0);

    private ConsensusMessageListener consensusMessageListener; // separate listener for consensus messages
    // with client commands
    private MessageListener messageListener; // general listener for client messages
    private final boolean logger = false;

    // constructors

    public AuthPerfLink(long ownId, PrivateKey privateKey, int port) throws IOException, Exception {
        this(ownId, privateKey, true, port);
    }

    public AuthPerfLink(long ownId, PrivateKey privateKey, boolean isReplica, int port) throws IOException, Exception {
        this(ownId, privateKey, isReplica, new DatagramSocket(port), Executors.newFixedThreadPool(50),
                new SessionManager(), new MemberManager(ownId, isReplica));
    }

    // Constructor with dependency injection for testing
    public AuthPerfLink(long ownId, PrivateKey privateKey, boolean isReplica, DatagramSocket socket,
            ExecutorService executorService, SessionManager sessionManager,
            MemberManager memberManagement) {
        this.ownId = ownId;
        this.privateKey = privateKey;
        this.isReplica = isReplica;
        this.datagramSocket = socket;
        this.executorService = executorService;
        // Single-threaded executor guarantees sends are serialized in submission
        this.sendExecutorService = Executors.newSingleThreadExecutor();
        this.sessionManager = sessionManager;
        this.memberManagement = memberManagement;
    }

    // public methods

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public void setConsensusMessageListener(ConsensusMessageListener listener) {
        this.consensusMessageListener = listener;
    }

    public void receive() {
        while (running.get() && !datagramSocket.isClosed()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                datagramSocket.receive(packet);
                if (running.get()) {
                    handlePacket(packet);
                }
            } catch (IOException e) {
                if (running.get()) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void shutdown() {
        running.set(false);

        for (Session session : sessionManager.getAllSessions()) {
            session.cleanup();
        }

        datagramSocket.close();
        executorService.shutdown();
        sendExecutorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            if (!sendExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                sendExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            sendExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void sendData(long receiverID, byte[] content) {
        try {
            if(receiverID == 101) {
                receiverID = 100;
            }
            
            Session session = sessionManager.getSession(receiverID);
            if (session == null) {
                session = startConnection(receiverID);
            }
 
            long messageId = messageIdCounter.getAndIncrement();

            if (content.length <= FRAGMENT_SIZE) {
                // single fragment 
                sendFragment(session, receiverID, messageId, 0, 1, content);
            } else {
                // split into fragments and send each one
                int totalFragments = (int) Math.ceil((double) content.length / FRAGMENT_SIZE);

                for (int i = 0; i < totalFragments; i++) {
                    int offset = i * FRAGMENT_SIZE;
                    int length = Math.min(FRAGMENT_SIZE, content.length - offset);
                    byte[] chunk = Arrays.copyOfRange(content, offset, offset + length);
                    sendFragment(session, receiverID, messageId, i, totalFragments, chunk);
                }
                logger("Sent message " + messageId + " as " + totalFragments
                        + " fragments to ID: " + receiverID);
            }
        } catch (Exception e) {
            System.err.println("Failed to send DATA: " + e.getMessage());
        }
    }

    private void sendFragment(Session session, long receiverID, long messageId, int fragmentIndex, 
                                int totalFragments, byte[] fragmentData) throws Exception {
        long seqNum = session.getAndAdvanceSequenceNumber();
        DataMessage msg = buildDataMessage(seqNum, fragmentData, session.getSessionKey(),
                                           messageId, fragmentIndex, totalFragments);
        
        send(msg, receiverID);
    }

    public void startConnectionWithAllMembers() {
        MemberInfo[] members = memberManagement.getMembers();
        List<Thread> threads = new java.util.ArrayList<>();
        for (MemberInfo member : members) {
            if (member.getId() == this.ownId)
                continue; // skip self
            if (isReplica && member.getId() < this.ownId)
                continue; // deterministic initiator: only connect to higher replica IDs
            if (sessionManager.getSession(member.getId()) != null)
                continue; // already connected
            final long memberId = member.getId();
            Thread t = new Thread(() -> {
                System.err.println(
                        "Attempting to connect to replica ID: " + memberId + " at address: " + member.getAddr());
                startConnection(memberId);
            }, "connect-to-" + memberId);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void connectToAllReplicas() {
        MemberInfo[] replicas = memberManagement.getMembers();
        System.err.println("Attempting to connect to all replicas. Found " + replicas.length + " members in config.");
        for (MemberInfo replica : replicas) {
            if (isReplica && replica.getId() < this.ownId) {
                continue; // deterministic initiator: only connect to higher replica IDs
            }
            try {
                System.err.println("Attempting to connect to replica ID: " + replica.getId() + " at address: "
                        + replica.getAddr());
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

            System.err.println(
                    "Starting connection to member ID: " + receiverID + " at address: " + receiverInfo.getAddr());

            // If a session is already fully established
            Session existingEstablished = sessionManager.getSession(receiverID);
            if (existingEstablished != null && existingEstablished.isConnectionEstablished()) {
                logger("Session already established with: " + receiverID + ", reusing");
                return existingEstablished;
            }

            // create ephemeral DH key pair for this session
            DHKeyAgreement dh = new DHKeyAgreement();
            dh.initialize();
            byte[] publicKey = dh.getEncodedPublicKey();

            // Sign the message
            String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey);
            String signature = Crypto.signMessage(this.ownId + ":" + publicKeyBase64, this.privateKey);

            // For clients, set clientID; for replicas, use default constructor (clientID =
            // -1)
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

            // Re-check under session lock to avoid races with concurrent inbound handshake
            // completion.
            synchronized (session) {
                if (session.isConnectionEstablished()) {
                    logger("Session became established concurrently with: " + receiverID + ", reusing");
                    return session;
                }

                // Do not overwrite an existing ephemeral DH (important for simultaneous
                // connections and retries).
                if (session.getEphemeralDH() == null) {
                    session.setEphemeralDH(dh);
                } else {
                    logger("Reusing existing ephemeral DH for " + receiverID);
                }
            }

            // Retry handshake with timeout
            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                logger("Sending CONNECTION_REQUEST to " + receiverID + " (attempt " + attempt + "/"
                        + maxRetries + ")");
                logger("Message content: {" +
                        "clientID: " + connectionRequest.getClientID() + ", " +
                        "type: " + connectionRequest.getType() + ", " +
                        "senderId: " + connectionRequest.getSenderId() + "}");
                send(connectionRequest, receiverID);
                logger(
                        "Sent CONNECTION_REQUEST to " + receiverID + " (attempt " + attempt + "/" + maxRetries + ")");

                // Wait for connection to be established
                if (session.waitForConnectionEstablished()) {
                    logger("Connection established with: " + receiverID);
                    return session;
                }

                logger("Connection timeout with " + receiverID + ", retrying...");
            }

            System.err.println(
                    "Failed to establish connection with " + receiverID + " after " + maxRetries + " attempts");
            return null;
        } catch (Exception e) {
            System.err.println("Failed to start connection: " + e.getMessage());
            return null;
        }
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
            logger("Message content: {" +
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
                    logger("Received connection request from client " + msg.getClientID() + " at " + addr);
                    senderId = msg.getClientID();
                    isClient = true;
                } else {
                    senderMember = memberManagement.getMember(msg.getSenderId());
                    if (senderMember == null) {
                        System.err.println("Unknown sender address for connection request: " + addr);
                        return;
                    }
                }
            }

            // Check if session already exists
            Session existingSession = sessionManager.getSession(senderId);
            if (existingSession != null && existingSession.isConnectionEstablished()) {
                // Fully established duplicate request - original ACK was probably lost, resend
                // it.
                logger("Received duplicate CONNECTION_REQUEST from " + senderId + ", resending ACK");

                DHKeyAgreement existingDH = existingSession.getEphemeralDH();
                if (existingDH == null) {
                    System.err.println(
                            "No ephemeral DH found for established session with " + senderId + ", cannot resend ACK");
                    return;
                }
                byte[] responderDHPublicKey = existingDH.getEncodedPublicKey();
                String responderKeyBase64 = Base64.getEncoder().encodeToString(responderDHPublicKey);
                String signature = Crypto.signMessage(this.ownId + ":" + responderKeyBase64, this.privateKey);

                ConnectionACKMessage reply = new ConnectionACKMessage(1, responderDHPublicKey, this.ownId, signature);
                datagramSocket.send(reply.toDatagramPacket(addr));
                return;
            }
            // If existingSession != null but NOT yet established, it is a placeholder we
            // created ourselves when we simultaneously initiated a connection to this same
            // peer (both sides called startConnection at the same time).

            PublicKey senderPublicKey;
            if (!isClient) {
                if (senderMember == null) {
                    System.err.println("Unknown sender for connection request: " + senderId + " from " + addr);
                    return;
                }
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

            // If we already have a local in-flight initiator DH for this peer (simultaneous
            // connect), reuse it so both sides derive the same key material
            // deterministically.
            // Otherwise, create a fresh responder DH keypair.
            DHKeyAgreement responderDH;
            if (existingSession != null && existingSession.getEphemeralDH() != null
                    && !existingSession.isConnectionEstablished()) {
                logger("Simultaneous connect with " + senderId + ", reusing local in-flight DH");
                responderDH = existingSession.getEphemeralDH();
            } else {
                responderDH = new DHKeyAgreement();
                responderDH.initialize();
            }

            byte[] responderDHPublicKey = responderDH.getEncodedPublicKey();
            SecretKey sessionKey = responderDH.computeSharedSecret(initiatorDHPublicKey);

            String responderKeyBase64 = Base64.getEncoder().encodeToString(responderDHPublicKey);
            String signature = Crypto.signMessage(this.ownId + ":" + responderKeyBase64, this.privateKey);

            Session session;
            if (existingSession != null) {
                // Update existing placeholder session
                session = existingSession;
                session.setSessionKey(sessionKey);
                if (session.getEphemeralDH() == null) {
                    session.setEphemeralDH(responderDH); // Store DH for ACK retransmissions
                }
            } else {
                // Create new session
                session = sessionManager.createSession(senderId, addr, sessionKey, isClient);
                session.setEphemeralDH(responderDH);
            }

            // Handshake: reply with seq 1 (request was seq 0)
            long replySeqNum = 1;
            ConnectionACKMessage reply = new ConnectionACKMessage(replySeqNum, responderDHPublicKey,
                    this.ownId, signature);

            // Send reply directly (no buffering for handshake)
            datagramSocket.send(reply.toDatagramPacket(addr));

            // After handshake, initialize session for data transfer (under lock to prevent
            // races)
            // - Both sender and receiver start at seq 2 for first data packet
            // - Both expect seq 2 as well
            synchronized (session) {
                if (!session.isConnectionEstablished()) {
                    session.setSequenceNumber(1); // Sets sendBase=1, nextSeqNum=2, expectedSeqNum=2
                    // Mark session as established ONLY after sequence numbers are set
                    session.markConnectionEstablished();
                }
            }

            logger("Connection reply sent to: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle connection request: " + e.getMessage());
        }
    }

    private void handleConnectionReply(ConnectionACKMessage msg, InetSocketAddress addr) {
        try {
            long senderId = msg.getSenderId();
            Session session = sessionManager.getSession(senderId);
            if (session == null) {
                System.err.println("No session found for connection reply from sender " + senderId + " at: " + addr);
                return;
            }

            // Verify it's the expected handshake ACK (seq 1)
            if (msg.getSequenceNumber() != 1) {
                System.err.println("Invalid connection reply sequence from: " + addr);
                return;
            }

            MemberInfo senderMember = memberManagement.getMember(senderId);
            if (senderMember == null) {
                System.err.println("Unknown sender " + senderId + " for connection reply from: " + addr);
                return;
            }

            PublicKey senderPublicKey = senderMember.getPublicSignatureKey();
            if (!Crypto.verifySignature(msg.getSignableContent().getBytes(), msg.getSignature(), senderPublicKey)) {
                System.err.println("Failed to verify signature for connection reply from: " + addr);
                return;
            }

            // Late/duplicate ACK for an already-established session should not overwrite
            // the
            // current key/state.
            if (session.isConnectionEstablished()) {
                logger("Ignoring duplicate CONNECTION_ACK for already-established session with: " + senderId);
                return;
            }

            // Get the responder DH public key from the ACK message
            byte[] responderDHPublicKey = msg.getDHPublicKey();

            // Compute shared secret
            DHKeyAgreement dh = session.getEphemeralDH();
            if (dh == null) {
                System.err.println("No ephemeral DH key found for session with sender " + senderId + " at: " + addr);
                return;
            }
            SecretKey sessionKey = dh.computeSharedSecret(responderDHPublicKey);
            session.setSessionKey(sessionKey);

            synchronized (session) {
                if (!session.isConnectionEstablished()) {
                    session.setSequenceNumber(1); // Sets sendBase=1, nextSeqNum=2, expectedSeqNum=2
                    session.markConnectionEstablished();
                }
            }

            logger("Connection reply verified and session established with: " + addr);
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
                    System.err
                            .println("Unknown sender address or client ID: " + addr + " (senderId: " + senderId + ")");
                    return;
                }
            }
            synchronized (session) {

                if (!verifyDataMessageHmac(msg, session, addr))  {
                    return;
                }

                // Successful HMAC proves both sides derived the same session key.
                if (!session.isConnectionEstablished()) {
                    session.setSequenceNumber(1);
                    session.markConnectionEstablished();
                    logger("Connection handshake completed upon first valid DATA from " + session.getSenderID());
                }

                // Sender ID check: only valid for non-fragmented messages
                if (msg.getTotalFragments() == 1) {
                    try {
                        String contentStr = new String(msg.getContent());
                        if (contentStr.contains("senderId")) {
                            String[] parts = contentStr.split("senderId\":");
                            if (parts.length > 1) {
                                String senderIdStr = parts[1].split("[,}]")[0].trim();
                                long senderId = Long.parseLong(senderIdStr);
                                if (senderId != session.getSenderID()) {
                                    System.err.println(
                                            "Sender ID in content does not match session sender ID for: " + addr);
                                    return;
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println(
                                "Failed to verify sender ID in content for: " + addr + " - " + e.getMessage());
                        return;
                    }
                }

                // In Selective Repeat: ACK immediately upon receipt (before buffering)
                sendDataAck(session, msg.getSequenceNumber());

                // Then buffer and deliver in-order packets
                List<DataMessage> ordered = session.addReceivedPacket(msg);
 
                // reassemble fragments before delivering to application
                for (DataMessage orderedMsg : ordered) {
                    tryReassembleAndDeliver(orderedMsg, session);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to handle DATA message from: " + addr + " - " + e.getMessage());
        }
    }

    private void tryReassembleAndDeliver(DataMessage msg, Session session) {
        if (msg.getTotalFragments() == 1) {
            deliverData(msg, session);
            return;
        }

        byte[] reassembled = session.addFragment(
            msg.getMessageId(), msg.getFragmentIndex(),
            msg.getTotalFragments(), msg.getContent()
        );

        if (reassembled != null) {
            DataMessage complete = new DataMessage(
                msg.getSequenceNumber(), reassembled, msg.getHmac(),
                session.getSenderID(), msg.getMessageId(), 0, 1
            );
            deliverData(complete, session);
        }
    }

    /**
     * Because now messages are buffered when they are received out of order
     * There is a need for a function that just delivers the message content to the
     * application layer
     * This function is called AFTER the message is verified in handleData
     * 
     * @param msg
     */
    private void deliverData(DataMessage msg, Session session) {
        String contentStr = new String(msg.getContent());
        if (session.isClientSession()) {
            messageListener.onMessage(contentStr);
        } else {
            consensusMessageListener.onConsensusMessage(contentStr);
        }
    }

    public void handleAck(DataACKMessage msg, InetSocketAddress addr) {
        try {
            Session session = getSessionForAddress(addr);
            if (session == null) {
                // Fallback for clients: they arent in the members list
                long senderId = msg.getSenderId();
                session = sessionManager.getSession(senderId);
                if (session == null)
                    return;
            }

            if (!session.isConnectionEstablished())
                return;

            byte[] expectedHmac = Crypto.generateHMAC(session.getSessionKey(), msg.toSignableBytes());
            if (!java.util.Arrays.equals(expectedHmac, msg.getHmac())) {
                System.err.println("HMAC verification failed for ACK from: " + addr);
                return;
            }
            long ackNum = msg.getSequenceNumber();
            synchronized (session) {
                session.handleAck(ackNum);
                // Notify any waiting senders that window may have space now
                session.notifyAll();
            }
            logger("ACK " + ackNum + " received from: " + addr);
        } catch (Exception e) {
            System.err.println("Failed to handle ACK: " + e.getMessage());
        }
    }

    // send helpers
    private void send(Message message, long id) throws Exception {
        sendExecutorService.submit(() -> sendPacket(message, id));
    }

    private void sendPacket(Message message, long id) {
        Session session = sessionManager.getSession(id);
        if (session == null) {
            session = startConnection(id);
        }

        // Selective Repeat: buffer data messages for retransmission with per-packet
        // timers
        if (message instanceof DataMessage) {
            DataMessage dataMsg = (DataMessage) message;

            // Wait if window is full - use synchronized wait/notify for efficient blocking
            synchronized (session) {
                long waitStart = System.currentTimeMillis();
                long maxWaitMs = 30_000; // 30 seconds max wait

                while (!session.canSend()) {
                    if (System.currentTimeMillis() - waitStart > maxWaitMs) {
                        System.err.println("Window full timeout after " + maxWaitMs
                                + "ms waiting to send to: " + session.getSenderID()
                                + " (unacked=" + session.getUnacknowledgedPackets().size() + ")");
                        return;
                    }
                    try {
                        // Wait for ACK to arrive and notify window has space
                        session.wait(1000); // 1 second timeout to allow periodic checks
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                // Now we have window space - buffer the packet while holding the lock
                try {
                    final Session finalSession = session;
                    final long seqNum = dataMsg.getSequenceNumber();
                    session.bufferPacket(seqNum, dataMsg, () -> handleTimeout(finalSession, seqNum));

                    // Send the packet after buffering
                    DatagramPacket packet = message.toDatagramPacket(session.getAddr());
                    datagramSocket.send(packet);

                    logger("Sent packet " + dataMsg.getSequenceNumber() + " to: " + session.getAddr());
                } catch (IOException e) {
                    System.err.println("Failed to send message: " + e.getMessage());
                }
            }
        } else {
            try {
                DatagramPacket packet = message.toDatagramPacket(session.getAddr());
                datagramSocket.send(packet);
                logger("Sent message seq " + message.getSequenceNumber() + " to: " + session.getAddr());
            } catch (IOException e) {
                System.err.println("Failed to send message: " + e.getMessage());
            }
        }
    }

    private void handleTimeout(Session session, long seqNum) {
        logger("Timeout for packet " + seqNum + " to: " + session.getSenderID() + ", retransmitting only this packet");

        // Selective Repeat: only retransmit the specific timed-out packet
        DataMessage msg = session.getUnacknowledgedPackets().get(seqNum);
        if (msg != null) {
            try {
                DatagramPacket packet = msg.toDatagramPacket(session.getAddr());
                datagramSocket.send(packet);
                logger("Retransmitted only packet " + seqNum + " to: " + session.getSenderID());
                // Restart timer for this specific packet
                session.restartTimerIfNeeded(seqNum);
            } catch (IOException e) {
                System.err.println("Failed to retransmit packet " + seqNum + ": " + e.getMessage());
            }
        } else {
            logger("Packet " + seqNum + " not found in unacknowledged buffer (already ACKed)");
        }
    }

    private void sendDataAck(Session session, long seqNum) {
        try {
            DataACKMessage ack = buildDataAck(seqNum, session.getSessionKey());
            datagramSocket.send(ack.toDatagramPacket(session.getAddr()));
            logger("ACK " + seqNum + " sent to: " + session.getAddr());
        } catch (Exception e) {
            System.err.println("Failed to send ACK: " + e.getMessage());
        }
    }

    // build message helpers

    private DataMessage buildDataMessage(long seqNum, byte[] content, SecretKey sessionKey, 
                            long messageId, int fragmentIndex, int totalFragments) throws Exception {

        DataMessage msgWithoutHmac = new DataMessage(seqNum, content, null, this.ownId,
                                                    messageId, fragmentIndex, totalFragments);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toSignableBytes());
        return new DataMessage(seqNum, content, hmac, this.ownId,
                            messageId, fragmentIndex, totalFragments);
    }

    private DataACKMessage buildDataAck(long seqNum, SecretKey sessionKey) throws Exception {
        DataACKMessage msgWithoutHmac = new DataACKMessage(seqNum, null, this.ownId);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toSignableBytes());
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

    private boolean verifyDataMessageHmac(DataMessage msg, Session session, InetSocketAddress addr) {
        try {
            byte[] expected = Crypto.generateHMAC(session.getSessionKey(), msg.withoutHmac().toSignableBytes());
            boolean valid   = java.util.Arrays.equals(expected, msg.getHmac());
            if (!valid) System.err.println("HMAC verification failed from: " + addr);
            return valid;
        } catch (Exception e) {
            System.err.println("HMAC verification error from: " + addr + " - " + e.getMessage());
            return false;
        }
    }

    private Session getSessionForAddress(InetSocketAddress addr) {
        MemberInfo senderMember = memberManagement.getMemberByAddress(addr);
        if (senderMember == null) {
            return null;
        }
        Session session = sessionManager.getSession(senderMember.getId());
        if (session == null) {
            return null;
        }
        return session;
    }

    private void logger(String message) {
        if (logger) { // set to true to enable debug logging
            String red = "\u001B[31m";
            String reset = "\u001B[0m";
            System.out.println(red + "[AuthPerfLink] " + message + reset);
        }
    }
}
