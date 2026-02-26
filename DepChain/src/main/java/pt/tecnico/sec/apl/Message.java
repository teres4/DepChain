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

    public Message(DatagramPacket packet) {
        // parse the message and initialize the fields
        // the message format is: MessageType:SequenceNumber:Data:HMAC
        String message = new String(packet.getData(), 0, packet.getLength());
        String[] parts = message.split(":");
        if (parts.length < 2) {
            // We need at least the message type and the sequence number
            throw new IllegalArgumentException("Malformed message");
        }
        this.type = MessageType.valueOf(parts[0]);
        this.sequenceNumber = Long.parseLong(parts[1]);
        if (parts.length > 2) {
            this.payload = parts[2].getBytes();
        } else {
            this.payload = new byte[0];
        }
        if (parts.length > 3) {
            this.hmac = parts[3].getBytes();
        } else {
            this.hmac = new byte[0];
        }
    }

    public MessageType getMessageType() {
        return type;
    }

    public toString toDatagramPacket() {
        // convert the message to a datagram packet
        return String.format("%s:%d:%s:%s", type.name(), sequenceNumber, new String(payload), new String(hmac))
                .getBytes();
        // TODO:
    }
}
