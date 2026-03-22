package pt.tecnico.sec.apl.message;

import java.nio.ByteBuffer;

public class DataMessage extends Message {

    // 1 byte type + 8 seqNum + 8 senderId + 8 messageId + 4 fragIdx + 4 totalFrags + 4 contentLen + 4 hmacLen
    public static final int HEADER_SIZE = 1 + 8 + 8 + 8 + 4 + 4 + 4 + 4 + 10;

    private byte[] content;
    private byte[] hmac;

    private long messageId = 0;
    private int fragmentIndex = 0;
    private int totalFragments = 1;

    public DataMessage(long sequenceNumber, byte[] content, byte[] hmac, long senderId) {
        super(MessageType.DATA, sequenceNumber, senderId);
        this.content = content;
        this.hmac = hmac;
    }

    public DataMessage(long sequenceNumber, byte[] content, byte[] hmac, long senderId, long messageId, int fragmentIndex, int totalFragments) {
        super(MessageType.DATA, sequenceNumber, senderId);
        this.content = content;
        this.hmac = hmac;
        this.messageId = messageId;
        this.fragmentIndex = fragmentIndex;
        this.totalFragments = totalFragments;
    }

    public DataMessage(long sequenceNumber, byte[] content, long senderId) {
        this(sequenceNumber, content, null, senderId);
    }

    public DataMessage(byte[] content, long senderId) {
        this(-1, content, null, senderId);
    }

    public byte[] toBytes() {
        int contentLen = content != null ? content.length : 0;
        int hmacLen = hmac != null ? hmac.length : 0;
        int totalSize = HEADER_SIZE + contentLen + hmacLen;

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.put((byte) MessageType.DATA.ordinal()); // type tag
        buf.putLong(getSequenceNumber());
        buf.putLong(getSenderId());
        buf.putLong(messageId);
        buf.putInt(fragmentIndex);
        buf.putInt(totalFragments);
        buf.putInt(contentLen);

        if (contentLen > 0) 
            buf.put(content);
        buf.putInt(hmacLen);
        
        if (hmacLen > 0) 
            buf.put(hmac);
        return buf.array();
    }

    public static DataMessage fromBytes(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.get(); 
        long seqNum = buf.getLong();
        long senderId = buf.getLong();
        long messageId = buf.getLong();
        int fragmentIndex = buf.getInt();
        int totalFrags = buf.getInt();
        int contentLen = buf.getInt();
        byte[] content = new byte[contentLen];
        
        if (contentLen > 0) 
            buf.get(content);

        int hmacLen = buf.getInt();
        byte[] hmac = new byte[hmacLen];
        
        if (hmacLen > 0) 
            buf.get(hmac);

        return new DataMessage(seqNum, content, hmac, senderId, messageId, fragmentIndex, totalFrags);
    }

    @Override
    public java.net.DatagramPacket toDatagramPacket(java.net.InetSocketAddress addr) {
        byte[] data = toBytes();
        return new java.net.DatagramPacket(data, data.length, addr);
    }

    public byte[] toSignableBytes() {
        return withoutHmac().toBytes();
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

    public long getMessageId() { 
        return messageId; 
    }

    public int getFragmentIndex(){ 
        return fragmentIndex; 
    }

    public int getTotalFragments(){ 
        return totalFragments; 
    }

    public void setSequenceNumber(long sequenceNumber) {
        super.setSequenceNumber(sequenceNumber);
    }

    @Override
    public DataMessage withoutHmac() {
        return new DataMessage(getSequenceNumber(), getContent(), null, this.getSenderId(), 
                               messageId, fragmentIndex, totalFragments);
    }

    @Override
    public String toString() {
        return "DataMessage{sequenceNumber=" + getSequenceNumber() + ", content=" + new String(getContent()) + ", senderId=" + this.getSenderId() + "}" + 
               " [messageId=" + messageId + ", fragmentIndex=" + fragmentIndex + ", totalFragments=" + totalFragments + "]";
    }
}