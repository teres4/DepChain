package pt.tecnico.sec.apl.message;

public class ConnectionACKMessage extends Message {
    private final long senderId;
    private final byte[] hmac;
    private final byte[] dhPublicKey; // Responder's DH public key
    private final String signature; 

    public ConnectionACKMessage(long sequenceNumber, byte[] hmac, byte[] receiverPublicKey, long senderId) {
        this(sequenceNumber, hmac, receiverPublicKey, senderId, null);
    }

    public ConnectionACKMessage(long sequenceNumber, byte[] hmac, byte[] receiverPublicKey, long senderId, String signature) {
        super(MessageType.CONNECTION_ACK, sequenceNumber);
        this.senderId = senderId;
        this.hmac = hmac;
        this.dhPublicKey = receiverPublicKey;
        this.signature = signature;
    }

    public byte[] getHmac() { return hmac; }
    
    public byte[] getDHPublicKey() { return dhPublicKey; }
    
    public String getSignature() { return signature; }
    
    public String getSignableContent() {
        return senderId + ":" + java.util.Base64.getEncoder().encodeToString(dhPublicKey);
    }

    @Override
    public ConnectionACKMessage withoutHmac() {
        return new ConnectionACKMessage(getSequenceNumber(), null, dhPublicKey, senderId, signature);
    }
    
    @Override
    public String toString() {
        return "ConnectionACKMessage{sequenceNumber=" + getSequenceNumber() + ", hasDHKey=" + (dhPublicKey != null) + ", hasSignature=" + (signature != null) + "}";
    }
}