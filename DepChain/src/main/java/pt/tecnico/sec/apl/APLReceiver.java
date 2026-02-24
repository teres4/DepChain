package main.java.pt.tecnico.sec.apl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class APLReceiver {
    public APLReceiver() throws IOException {
        DatagramSocket datagramSocket = new DatagramSocket(8080);

        while(true) {
            byte[] buffer = new byte[1024]; // random buffer for now
            DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
            datagramSocket.receive(datagramPacket);

            String message = new String(buffer).trim();
            System.out.println("Received message: " + message);

            InetAddress senderAddress = datagramPacket.getAddress();
            int senderPort = datagramPacket.getPort();

            // TODO: create ACK response
            message = "ACK";
            buffer = message.getBytes();
            datagramPacket = new DatagramPacket(buffer, buffer.length, senderAddress, senderPort);
            datagramSocket.send(datagramPacket);
        }
    }
}
