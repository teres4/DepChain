package pt.tecnico.sec.consensus.message;

import com.google.gson.Gson;
import pt.tecnico.sec.apl.message.DataMessage;

public abstract class HotStuffMessage {
    private final HotStuffMessageType hotStuffType;
    private final int viewNumber;
    private final long senderId;

    protected HotStuffMessage(HotStuffMessageType type, int viewNumber, long senderId) {
        this.hotStuffType = type;
        this.viewNumber = viewNumber;
        this.senderId = senderId;
        // Set actual content after construction (now that all fields are initialized)
    }

    public HotStuffMessageType getHotStuffType() {
        return hotStuffType;
    }

    public int getViewNumber() {
        return viewNumber;
    }

    public long getSenderId() {
        return senderId;
    }

    public boolean matches(HotStuffMessageType type, int viewNumber) {
        return this.hotStuffType == type && this.viewNumber == viewNumber;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    private static class TypeOnly {
        HotStuffMessageType hotStuffType;
    }

    // Parse HotStuffMessage from DataMessage content
    public static HotStuffMessage fromDataMessage(DataMessage dataMessage) {
        String json = new String(dataMessage.getContent());
        return fromJson(json);
    }

    public static HotStuffMessage fromJson(String json) {
        Gson gson = new Gson();
        HotStuffMessageType type = gson.fromJson(json, TypeOnly.class).hotStuffType;

        return switch (type) {
            case NEW_VIEW -> gson.fromJson(json, NewViewMessage.class);
            case NEW_COMMAND -> gson.fromJson(json, NewCommandMessage.class);
            case PREPARE -> gson.fromJson(json, PrepareMessage.class);
            case PREPARE_VOTE -> gson.fromJson(json, PrepareVoteMessage.class);
            case PRE_COMMIT -> gson.fromJson(json, PreCommitMessage.class);
            case PRE_COMMIT_VOTE -> gson.fromJson(json, PreCommitVoteMessage.class);
            case COMMIT -> gson.fromJson(json, CommitMessage.class);
            case COMMIT_VOTE -> gson.fromJson(json, CommitVoteMessage.class);
            case DECIDE -> gson.fromJson(json, DecideMessage.class);
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }

    @Override
    public String toString() {
        return "HotStuffMessage{type=" + hotStuffType + ", view=" + viewNumber + ", sender=" + senderId + "}";
    }
}