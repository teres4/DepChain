package pt.tecnico.sec.application;

import com.google.gson.Gson;

public class DepChainMessage {
    private final ApplicationMessageType messageType;
    private final String payload; // JSON string of the actual message (HotStuffMessage or Command)

    public DepChainMessage(ApplicationMessageType messageType, String payload) {
        this.messageType = messageType;
        this.payload = payload;
    }

    public ApplicationMessageType getMessageType() {
        return messageType;
    }

    public String getPayload() {
        return payload;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static DepChainMessage fromJson(String json) {
        return new Gson().fromJson(json, DepChainMessage.class);
    }

    public byte[] toBytes() {
        return toJson().getBytes();
    }

    public static DepChainMessage fromBytes(String content) {
        return fromJson(new String(content));
    }
}
