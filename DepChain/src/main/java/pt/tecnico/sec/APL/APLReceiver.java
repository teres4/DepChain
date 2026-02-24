package main.java.pt.tecnico.sec.apl;

import java.net.DatagramSocket;

public class APLReceiver {

    DatagramSocket datagramSocket = new DatagramSocket(8080);
    System.out.println("Receiver ta up!");
    while(true) {
        byte[] buffer = new byte[1500];
        DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
        datagramSocket.receive(datagramPacket);
    }
}
