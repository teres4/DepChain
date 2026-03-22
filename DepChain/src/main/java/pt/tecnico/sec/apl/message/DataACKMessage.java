package pt.tecnico.sec.apl.message;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;

public class DataACKMessage extends Message {

    public static final int FIXED_HEADER_SIZE = 1 + 8 + 8 + 4; // tag + seqNum + senderId + hmacLen

    private final byte[] hmac;

    public DataACKMessage(long sequenceNumber, byte[] hmac, long senderId) {
        super(MessageType.ACK, sequenceNumber, senderId);
        this.hmac = hmac;
    }

    public byte[] getHmac() { return hmac; }

    public byte[] toBytes() {
        int hmacLen = hmac != null ? hmac.length : 0;
        int totalSize = FIXED_HEADER_SIZE + hmacLen;

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.put((byte) MessageType.ACK.ordinal());
        buf.putLong(getSequenceNumber());
        buf.putLong(getSenderId());
        buf.putInt(hmacLen);
        if (hmacLen > 0) 
            buf.put(hmac);
        return buf.array();
    }

    public static DataACKMessage fromBytes(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.get(); 
        long  seqNum = buf.getLong();
        long sender = buf.getLong();
        int  hmacLen = buf.getInt();
        byte[] hmac = new byte[hmacLen];
        if (hmacLen > 0) 
            buf.get(hmac);
        return new DataACKMessage(seqNum, hmac, sender);
    }

    @Override
    public java.net.DatagramPacket toDatagramPacket(java.net.InetSocketAddress addr) {
        byte[] data = toBytes();
        return new DatagramPacket(data, data.length, addr);
    }

    @Override
    public DataACKMessage withoutHmac() {
        return new DataACKMessage(getSequenceNumber(), null, this.getSenderId());
    }

    public byte[] toSignableBytes() {
        return withoutHmac().toBytes();
    }

    @Override
    public String toString() {
        return "DataACKMessage{sequenceNumber=" + getSequenceNumber() + "}";
    }
}