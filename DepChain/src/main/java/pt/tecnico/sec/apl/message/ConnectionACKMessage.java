package pt.tecnico.sec.apl.message;

public class ConnectionACKMessage extends Message {
    private final byte[] hmac;

    public ConnectionACKMessage(long sequenceNumber, byte[] hmac) {
        super(MessageType.CONNECTION_ACK, sequenceNumber);
        this.hmac = hmac;
    }

    public byte[] getHmac() { return hmac; }

    @Override
    public ConnectionACKMessage withoutHmac() {
        return new ConnectionACKMessage(getSequenceNumber(), null);
    }
    
    @Override
    public String toString() {
        return "ConnectionACKMessage{sequenceNumber=" + getSequenceNumber() + "}";
    }
}