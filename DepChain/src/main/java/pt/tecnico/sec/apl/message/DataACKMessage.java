package pt.tecnico.sec.apl.message;

public class DataACKMessage extends Message {
    private final byte[] hmac;

    public DataACKMessage(long sequenceNumber, byte[] hmac, long senderId) {
        super(MessageType.ACK, sequenceNumber, senderId);
        this.hmac = hmac;
    }

    public byte[] getHmac() { return hmac; }

    @Override
    public DataACKMessage withoutHmac() {
        return new DataACKMessage(getSequenceNumber(), null, this.getSenderId());
    }

    @Override
    public String toString() {
        return "DataACKMessage{sequenceNumber=" + getSequenceNumber() + "}";
    }
}