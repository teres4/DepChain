package pt.tecnico.sec.apl.message;

public class DataMessage extends Message {
    private byte[] content;
    private byte[] hmac;

    public DataMessage(long sequenceNumber, byte[] content, byte[] hmac, long senderId) {
        super(MessageType.DATA, sequenceNumber, senderId);
        this.content = content;
        this.hmac = hmac;
    }

    public DataMessage(long sequenceNumber, byte[] content, long senderId) {
        this(sequenceNumber, content, null, senderId);
    }

    public DataMessage(byte[] content, long senderId) {
        this(-1, content, null, senderId);
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
        return new DataMessage(getSequenceNumber(), getContent(), null, this.getSenderId());
    }

    @Override
    public String toString() {
        return "DataMessage{sequenceNumber=" + getSequenceNumber() + ", content=" + new String(getContent()) + ", senderId=" + this.getSenderId() + "}";
    }
}