package pt.tecnico.sec.apl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import pt.tecnico.sec.utils.MessageType;

public class AuthPerfLink {

    DatagramSocket datagramSocket = new DatagramSocket(8080);

    private final int POOL_SIZE = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(POOL_SIZE);

    private SessionManager sessionManager = new SessionManager();

    public AuthPerfLink() throws IOException {
    }

    public void receive() {
        while (true) {
            byte[] buffer = new byte[1024];
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

        Message msg = parseMessage(packet);

    }

    public Message parseMessage(DatagramPacket packet) {
        // parse the message and return a Message object
        // the message format is: MessageType:SequenceNumber:Data:HMAC
        String message = new String(packet.getData(), 0, packet.getLength());
        String[] parts = message.split(":");
        if (parts.length < 2) {
            // We need at least the message type and the sequence number
            return null; // ignore malformed message
        }

        MessageType type;
        try {
            type = MessageType.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            // Unknown message type
            return null; // ignore malformed message
        }

        switch (type) {
            // TODO: implement message parsing and session management logic here
            default:
                return null; // ignore unknown message types
        }
    }

}
