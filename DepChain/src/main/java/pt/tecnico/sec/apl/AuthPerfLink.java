package pt.tecnico.sec.apl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class AuthPerfLink {

    DatagramSocket datagramSocket = new DatagramSocket(8080);

    public AuthPerfLink() throws IOException {
    }

    public void send(String message) {
        DatagramPacket packet = new DatagramPacket(message.getBytes(), message.length());
        try {
            datagramSocket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String receive() {
        byte[] buffer = new byte[1024]; // random buffer for now
        DatagramPacket datagramPacket;
        datagramPacket = new DatagramPacket(buffer, buffer.length);
        try {
            datagramSocket.receive(datagramPacket);
        } catch (IOException e) {
            e.printStackTrace(); 
        }
    }
}

