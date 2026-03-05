package pt.tecnico.sec.apl.message;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;

import com.google.gson.Gson;

public abstract class Message {
    private final MessageType type;
    private long sequenceNumber;
    private long senderId; // ID of the replica that sent this message (for logging/debugging)

    protected Message(MessageType type, long sequenceNumber, long senderId) {
        this.type = type;
        this.sequenceNumber = sequenceNumber;
        this.senderId = senderId;
    }

    public MessageType getType() {
        return type;
    }

    public long getSenderId() {
        return senderId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public DatagramPacket toDatagramPacket(InetSocketAddress addr) {
        byte[] data = toJsonString().getBytes();
        return new DatagramPacket(data, data.length, addr);
    }

    public abstract Message withoutHmac();

    public String toJsonString() {
        return new Gson().toJson(this);
    }

    public void setSequenceNumber(long sequenceNumber) {
        // This method is only used for DataMessages, which override it to allow setting
        // the sequence number after creation.
        this.sequenceNumber = sequenceNumber;
    }

    private static class TypeOnly {
        MessageType type;
    }

    public static Message fromPacket(DatagramPacket packet) {
        Gson gson = new Gson();
        String json = new String(packet.getData(), 0, packet.getLength());

        MessageType type = gson.fromJson(json, TypeOnly.class).type;

        return switch (type) {
            case CONNECTION_REQUEST -> gson.fromJson(json, ConnectionRequestMessage.class);
            case CONNECTION_ACK -> gson.fromJson(json, ConnectionACKMessage.class);
            case DATA -> gson.fromJson(json, DataMessage.class);
            case ACK -> gson.fromJson(json, DataACKMessage.class);
            default -> throw new IllegalArgumentException("Unknown message type: " + type);
        };
    }

    @Override
    public String toString() {
        return "Message{type=" + type + ", sequenceNumber=" + sequenceNumber + ", senderId=" + senderId + "}";
    }
}