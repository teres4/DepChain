package pt.tecnico.sec.apl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;
import pt.tecnico.sec.utils.MemberManagement;
import pt.tecnico.sec.utils.MessageType;

public class AuthPerfLink {

    DatagramSocket datagramSocket = new DatagramSocket(8080);

    private final int POOL_SIZE = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(POOL_SIZE);

    private long ownId;
    private PrivateKey privateKey;

    private SessionManager sessionManager = new SessionManager();
    private MemberManagement memberManagement = new MemberManagement();

    public AuthPerfLink(long ownId, PrivateKey privateKey) throws IOException {
        this.ownId = ownId;
        this.privateKey = privateKey;
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
        while (true) {
            byte[] buffer = new byte[1024]; // TODO check and set appropriate buffer size
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                datagramSocket.receive(packet);
                Message message = new Message(packet);
                System.out.println("Received: " + message);
                executorService.submit(() -> handlePacket(packet));
            } catch (IOException e) {
                e.printStackTrace();
            }
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

    // connection request has message type, sender id, seq number 0, session key encrypted 
    // with receiver public key, and digital signature using sender private key
    public void handleConnectionRequest(Message msg, InetSocketAddress addr) {
        try {
            // Verify message contents
            if (msg.getType() != MessageType.CONNECTION_REQUEST) {
                System.err.println("Invalid message type for connection request from: " + addr);
                return;
            }
            if (msg.getSequenceNumber() != 0) {
                System.err.println("Invalid sequence number in connection request from: " + addr);
                return;
            }

            // Verify digital signature

            // TODO PLS VERIFY THIS
            // does this work? maybe the sender id should be in a separate field in the message instead of inside payload
            // or maybe the payload should be a json object with different fields for sender id, session key, etc
            long senderID = Long.parseLong(new String(msg.getPayload())); // MIGHT HAVE TO REPLACE THIS WITH ACTUAL PAYLOAD PARSING TO EXTRACT SENDER ID

            PublicKey senderPublicKey = memberManagement.getMember(senderID).getPublicKey();
            if (!Crypto.verifySignature(msg.getPayload(), msg.getSignature(), senderPublicKey)) {
                System.err.println("Failed to verify signature for connection request from: " + addr);
                return;
            }

            // Extract session key
            byte[] sessionKeyBytes = Crypto.decryptWithPrivateKey(msg.getEncryptedSessionKey(), this.privateKey);
            SecretKey sessionKey = Crypto.bytesToSecretKey(sessionKeyBytes);
            sessionManager.createSession(senderID, addr, sessionKey);

            // Prepare reply and send it back to the sender
            

            long sequenceNumber = 1;
            // TODO: decrypt HMAC and save it on a variable
            Message reply = new Message(MessageType.CONNECTION_REPLY, new byte[0], sequenceNumber, new byte[0]); // FIXME:
                                                                                                                 // send
                                                                                                                 // HMAC

            DatagramPacket replyPacket = reply.toDatagramPacket(addr);
            datagramSocket.send(replyPacket);

            System.out.println("Connection reply sent to: " + addr);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to handle connection request: " + e.getMessage());
        }
    }

    public void handleConnectionReply(Message msg, InetSocketAddress addr) {
        try {
            long senderID = Long.parseLong(new String(msg.getPayload()));
            Session session = sessionManager.getSession(senderID);

            if (session == null) {
                System.err.println("No session found for sender ID: " + senderID);
                return;
            }

            // TODO: checking HMAC wrong, should compare with contents of the entire reply
            byte[] expectedHmac = Crypto.generateHMAC(session.getSessionKey(), msg.getPayload());
            if (!Arrays.equals(expectedHmac, msg.getHmac())) {
                System.err.println("HMAC verification failed for connection reply from: " + addr);
                return;
            }

            if (msg.getSequenceNumber() != 1) {
                System.err.println("Invalid sequence number in connection reply from: " + addr);
                return;
            }

            session.replyReceived();
            System.out.println("Connection reply verified and session established with: " + addr);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to handle connection reply: " + e.getMessage());
        }
    }

    public void handleAck(Message msg, InetSocketAddress addr) {
        // identificar a sessao
        // marcar a mensagem como acknowledged de acordo com o sequence number
    }

    public void handleData(Message msg, InetSocketAddress addr) {
        // verify the HMAC and the sequence number, and if they are correct, process the
        // message
        // session.replyReceived();
    }

    public DatagramPacket prepareResponse() {
        return msg.toDatagramPacket(session.getAddr());
    }

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

            // send connection request with encrypted session key
            long sequenceNumber = 0;
            Message connectionRequest = new Message(MessageType.CONNECTION_REQUEST, encryptedSessionKey, sequenceNumber);
            Session session = sessionManager.createSession(receiverID, receiverInfo.getAddr(), sessionKey);

            send(connectionRequest, session); // send connection request and wait for reply
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to start connection: " + e.getMessage());
        }
    }

}
