package pt.tecnico.sec.apl.message;

public class ConnectionRequestMessage extends Message {
    private final long senderId;
    private final byte[] dhPublicKey; // Sender's DH public key
    private final String signature;

    public ConnectionRequestMessage(long senderId, byte[] dhPublicKey, String signature) {
        super(MessageType.CONNECTION_REQUEST, 0);
        this.senderId = senderId;
        this.dhPublicKey = dhPublicKey;
        this.signature = signature;
    }

    public long getSenderId() { return senderId; }
    public byte[] getDhPublicKey() { return dhPublicKey; }
    public String getSignature() { return signature; }

    @Override
    public ConnectionRequestMessage withoutHmac() {
        return this;
    }

    public String getSignableContent() {
        return senderId + ":" + java.util.Base64.getEncoder().encodeToString(dhPublicKey);
    }

    @Override
    public String toString() {
        return "ConnectionRequestMessage{senderId=" + senderId + ", sequenceNumber=" + getSequenceNumber() + "}";
    }
}