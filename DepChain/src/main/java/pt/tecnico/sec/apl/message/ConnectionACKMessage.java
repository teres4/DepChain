package pt.tecnico.sec.apl.message;

public class ConnectionACKMessage extends Message {
    private final byte[] dhPublicKey; // Responder's DH public key
    private final String signature; 

    public ConnectionACKMessage(long sequenceNumber, byte[] receiverPublicKey, long senderId) {
        this(sequenceNumber, receiverPublicKey, senderId, null);
    }

    public ConnectionACKMessage(long sequenceNumber, byte[] receiverPublicKey, long senderId, String signature) {
        super(MessageType.CONNECTION_ACK, sequenceNumber, senderId);
        this.dhPublicKey = receiverPublicKey;
        this.signature = signature;
    }
    
    public byte[] getDHPublicKey() { return this.dhPublicKey; }
    
    public String getSignature() { return this.signature; }
    
    public String getSignableContent() {
        return this.getSenderId() + ":" + java.util.Base64.getEncoder().encodeToString(this.dhPublicKey);
    }

    @Override
    public ConnectionACKMessage withoutHmac() {
        return new ConnectionACKMessage(getSequenceNumber(), dhPublicKey, this.getSenderId(), null);
    }
    
    @Override
    public String toString() {
        return "ConnectionACKMessage{sequenceNumber=" + getSequenceNumber() + ", hasDHKey=" + (dhPublicKey != null) + ", hasSignature=" + (signature != null) + "}";
    }
}