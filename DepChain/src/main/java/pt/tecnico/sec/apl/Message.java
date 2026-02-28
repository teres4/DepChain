package pt.tecnico.sec.apl;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;

import com.google.gson.Gson;

import pt.tecnico.sec.utils.MessageType;

public class Message {
    private final MessageType type;
    private final byte[] payload;
    private final long sequenceNumber;

    // optional
    private byte[] hmac;
    private String signature;
    private String encryptedSessionKey;

    /*
     * CONNECTION_REQUEST:
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

    public Message(MessageType type, byte[] payload, long sequenceNumber) {
        this.type = type;
        this.payload = payload;
        this.sequenceNumber = sequenceNumber;
    }

    public Message(DatagramPacket packet) {
        Gson gson = new Gson();
        String jsonMessage = new String(packet.getData(), 0, packet.getLength());
        Message parsedMessage = gson.fromJson(jsonMessage, Message.class);

        this.type = parsedMessage.type;
        this.payload = parsedMessage.payload;
        this.sequenceNumber = parsedMessage.sequenceNumber;
        if (parsedMessage.hmac != null) 
            this.hmac = parsedMessage.hmac;
        
        if (parsedMessage.signature != null)
            this.signature = parsedMessage.signature;

        if (parsedMessage.encryptedSessionKey != null)
            this.encryptedSessionKey = parsedMessage.encryptedSessionKey;
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

    public void setHmac(byte[] hmac) {
        this.hmac = hmac;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getEncryptedSessionKey() {
        return encryptedSessionKey;
    }

    public void setEncryptedSessionKey(String encryptedSessionKey) {
        this.encryptedSessionKey = encryptedSessionKey;
    }

    public DatagramPacket toDatagramPacket(InetSocketAddress addr) {
        Gson gson = new Gson();
        String jsonMessage = gson.toJson(this);
        byte[] data = jsonMessage.getBytes();
        return new DatagramPacket(data, data.length, addr);
    }

    public String toJsonString() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", payload=" + new String(payload) +
                ", sequenceNumber=" + sequenceNumber +
                '}';
    }
}
