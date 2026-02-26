package pt.tecnico.sec.apl;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Arrays;
import pt.tecnico.sec.utils.MessageType;
import pt.tecnico.sec.utils.Crypto;

public class AuthPerfLink {

    DatagramSocket datagramSocket = new DatagramSocket(8080);

    private final int POOL_SIZE = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(POOL_SIZE);

    private SessionManager sessionManager = new SessionManager();

    public AuthPerfLink() throws IOException {
    }

    public void createSession(long senderID) {
        sessionManager.createSession(senderID);
    }

    // send authenticated message - gotta include sequence number and hmac
    // wait for ack and retransmit if not received within timeout

    // session request --> message + sequence number + sender ID + secret key
    // message --> message + sequence number + hmac +
    public void send(Message message, Session session) {

        // check if theres a session with senderId
        DatagramPacket packet = new DatagramPacket(message.toString().getBytes(), message.toString().getBytes().length,
                session.getAddr());

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
                String message = new String(packet.getData(), 0, packet.getLength());
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
                handleData(msg);
                break;
            default:
                break;
        }
    }

    public void handleConnectionRequest(Message msg, InetSocketAddress addr) {
        try {
            long senderID = Long.parseLong(new String(msg.getPayload()));
            // TODO: maybe create session only after verifying contents of the message
            Session session = sessionManager.createSession(senderID);

            long sequenceNumber = 1;
            // TODO: decrypt HMAC and save it on a variable
            Message reply = new Message(MessageType.CONNECTION_REPLY, new byte[0], sequenceNumber, new byte[0]); // FIXME: send HMAC

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
            byte[] expectedHmac = Crypto.generateHMAC(session.getSessionKey(), msg.getSequenceNumber().getBytes());
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

    public void handleData(Message msg) {
        // verify the HMAC and the sequence number, and if they are correct, process the
        // message
        // session.replyReceived();
    }

}
