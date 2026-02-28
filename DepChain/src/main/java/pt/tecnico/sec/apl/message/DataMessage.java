package pt.tecnico.sec.apl.message;

public class DataMessage extends Message {
    private final byte[] content;
    private final byte[] hmac;

    public DataMessage(long sequenceNumber, byte[] content, byte[] hmac) {
        super(MessageType.DATA, sequenceNumber);
        this.content = content;
        this.hmac = hmac;
    }

    public byte[] getContent() { return content; }
    public byte[] getHmac() { return hmac; }

    @Override
    public DataMessage withoutHmac() {
        return new DataMessage(getSequenceNumber(), content, null);
    }

    @Override
    public String toString() {
        return "DataMessage{sequenceNumber=" + getSequenceNumber() + ", content=" + new String(content) + "}";
    }
}