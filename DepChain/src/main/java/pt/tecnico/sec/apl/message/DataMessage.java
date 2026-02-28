package pt.tecnico.sec.apl.message;

public class DataMessage extends Message {
    private byte[] content;
    private byte[] hmac;

    public DataMessage(long sequenceNumber, byte[] content, byte[] hmac) {
        super(MessageType.DATA, sequenceNumber);
        this.content = content;
        this.hmac = hmac;
    }

    public DataMessage(long sequenceNumber, byte[] content) {
        this(sequenceNumber, content, null);
    }

    public DataMessage(byte[] content) {
        this(-1, content, null);
    }

    public byte[] getContent() {
        return content;
    }

    protected void setContent(byte[] content) {
        this.content = content;
    }

    public byte[] getHmac() {
        return hmac;
    }

    public void setHmac(byte[] hmac) {
        this.hmac = hmac;
    }

    public void setSequenceNumber(long sequenceNumber) {
        super.setSequenceNumber(sequenceNumber);
    }

    @Override
    public DataMessage withoutHmac() {
        return new DataMessage(getSequenceNumber(), getContent(), null);
    }

    @Override
    public String toString() {
        return "DataMessage{sequenceNumber=" + getSequenceNumber() + ", content=" + new String(getContent()) + "}";
    }
}