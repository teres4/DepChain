package pt.tecnico.sec.apl.message;

public class DataACKMessage extends Message {
    private final byte[] hmac;

    public DataACKMessage(long sequenceNumber, byte[] hmac) {
        super(MessageType.ACK, sequenceNumber);
        this.hmac = hmac;
    }

    public byte[] getHmac() { return hmac; }

    @Override
    public DataACKMessage withoutHmac() {
        return new DataACKMessage(getSequenceNumber(), null);
    }

    @Override
    public String toString() {
        return "DataACKMessage{sequenceNumber=" + getSequenceNumber() + "}";
    }
}