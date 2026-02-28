package pt.tecnico.sec.apl.message;

public class ConnectionRequestMessage extends Message {
    private final long senderId;
    private final String encryptedSessionKey;
    private final String signature;

    public ConnectionRequestMessage(long senderId, String encryptedSessionKey, String signature) {
        super(MessageType.CONNECTION_REQUEST, 0);
        this.senderId = senderId;
        this.encryptedSessionKey = encryptedSessionKey;
        this.signature = signature;
    }

    public long getSenderId() { return senderId; }
    public String getEncryptedSessionKey() { return encryptedSessionKey; }
    public String getSignature() { return signature; }

    @Override
    public ConnectionRequestMessage withoutHmac() {
        return this;
    }

    public String getSignableContent() {
        return senderId + ":" + encryptedSessionKey;
    }

    @Override
    public String toString() {
        return "ConnectionRequestMessage{senderId=" + senderId + ", sequenceNumber=" + getSequenceNumber() + "}";
    }
}