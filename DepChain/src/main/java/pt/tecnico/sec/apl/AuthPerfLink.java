package pt.tecnico.sec.apl;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        // create new session and send connection reply
    }

    public void handleConnectionReply(Message msg, InetSocketAddress addr) {
        // verify the HMAC and the sequence number, and if they are correct, mark the
        // session as established
        // session.replyReceived();
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
