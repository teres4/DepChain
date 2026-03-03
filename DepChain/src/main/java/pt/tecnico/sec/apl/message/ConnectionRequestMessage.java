package pt.tecnico.sec.apl.message;

public class ConnectionRequestMessage extends Message {
    private final long senderId;
    private final byte[] encryptedSessionKey;
    private final String signature;

    public ConnectionRequestMessage(long senderId, byte[] encryptedSessionKey, String signature) {
        super(MessageType.CONNECTION_REQUEST, 0);
        this.senderId = senderId;
        this.encryptedSessionKey = encryptedSessionKey;
        this.signature = signature;
    }

    public long getSenderId() { return senderId; }
    public byte[] getEncryptedSessionKey() { return encryptedSessionKey; }
    public String getSignature() { return signature; }

    @Override
    public ConnectionRequestMessage withoutHmac() {
        return this;
    }

    public String getSignableContent() {
        return senderId + ":" + java.util.Base64.getEncoder().encodeToString(encryptedSessionKey);
    }

    @Override
    public String toString() {
        return "ConnectionRequestMessage{senderId=" + senderId + ", sequenceNumber=" + getSequenceNumber() + "}";
    }
}