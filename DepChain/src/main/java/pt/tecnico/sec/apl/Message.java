package pt.tecnico.sec.apl;

import java.net.DatagramPacket;

import javax.xml.crypto.Data;

import pt.tecnico.sec.utils.*;

public class Message {
    private final MessageType type;
    private final byte[] payload;
    private final long sequenceNumber;

    // optional
    private final byte[] hmac;

    public Message(MessageType type, byte[] payload, long sequenceNumber, byte[] hmac) {
        this.type = type;
        this.payload = payload;
        this.sequenceNumber = sequenceNumber;
        this.hmac = hmac;
    }

    public MessageType getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }
}
