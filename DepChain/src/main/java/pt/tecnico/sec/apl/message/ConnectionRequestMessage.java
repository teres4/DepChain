package pt.tecnico.sec.apl.message;

import java.sql.Connection;

public class ConnectionRequestMessage extends Message {
    private final byte[] dhPublicKey; // Sender's DH public key
    private final String signature;
    private long clientID; //

    public ConnectionRequestMessage(long senderId, byte[] dhPublicKey, String signature, long clientID) {
        super(MessageType.CONNECTION_REQUEST, 0, senderId);
        this.dhPublicKey = dhPublicKey;
        this.signature = signature;
        this.clientID = clientID;
    }

    public ConnectionRequestMessage(long senderId, byte[] dhPublicKey, String signature) {
        super(MessageType.CONNECTION_REQUEST, 0, senderId);
        this.dhPublicKey = dhPublicKey;
        this.signature = signature;
        this.clientID = -1; // its a replica, not a client
    }

    public long getClientID() {
        return clientID;
    }

    public byte[] getDhPublicKey() {
        return dhPublicKey;
    }

    public String getSignature() {
        return signature;
    }

    @Override
    public ConnectionRequestMessage withoutHmac() {
        return this;
    }

    public String getSignableContent() {
        return this.getSenderId() + ":" + java.util.Base64.getEncoder().encodeToString(dhPublicKey);
    }

    @Override
    public String toString() {
        return "ConnectionRequestMessage{senderId=" + this.getSenderId() + ", sequenceNumber=" + getSequenceNumber()
                + "}";
    }
}