package pt.tecnico.sec.apl;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class APLReceiver {

    public static void main(String[] args) throws Exception {
        DatagramSocket datagramSocket = new DatagramSocket(8080);
        System.out.println("Receiver ta up!");
        while(true) {
            byte[] buffer = new byte[1500];
            DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
            datagramSocket.receive(datagramPacket);
        }
    }
}
