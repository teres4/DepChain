package pt.tecnico.sec.apl;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;

import javax.xml.crypto.Data;

import com.google.gson.Gson;

import pt.tecnico.sec.utils.*;

public class Message {
    private final MessageType type;
    private final byte[] payload;
    private final long sequenceNumber;

    // optional
    private final byte[] hmac;

    /*
     * CONNECTION_REQUEST:
     * sender id
     * seq_number 0
     * session key encrypted with receiver public key
     * digital signature using sender private key
     * 
     * CONNECTION_REPLY:
     * seq_number 1
     * hmac using session key
     * 
     * DATA:
     * content
     * seq_number
     * hmac using session key
     * 
     * ACK:
     * seq_number
     * hmac using session key
     */
    public Message(MessageType type, byte[] payload, long sequenceNumber, byte[] hmac) {
        this.type = type;
        this.payload = payload;
        this.sequenceNumber = sequenceNumber;
        this.hmac = hmac;
    }

    public Message(DatagramPacket packet) {
        Gson gson = new Gson();
        String jsonMessage = new String(packet.getData(), 0, packet.getLength());
        Message parsedMessage = gson.fromJson(jsonMessage, Message.class);

        this.type = parsedMessage.type;
        this.payload = parsedMessage.payload;
        this.sequenceNumber = parsedMessage.sequenceNumber;
        this.hmac = parsedMessage.hmac;
    }

    public MessageType getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public byte[] getHmac() {
        return hmac;
    }

    public DatagramPacket toDatagramPacket(InetSocketAddress addr) {
        Gson gson = new Gson();
        String jsonMessage = gson.toJson(this);
        byte[] data = jsonMessage.getBytes();
        return new DatagramPacket(data, data.length, addr);
    }
}
