package pt.tecnico.sec.client.message;

import com.google.gson.Gson;

import pt.tecnico.sec.apl.message.DataMessage;
import pt.tecnico.sec.blockchain.Transaction;

public class Command {
    private final CommandType type;
    private String content;
    private long senderId;

    public Command(CommandType type, long senderId, String content, String signature) {
        this.type = type;
        this.content = content;
        this.senderId = senderId;
    }

    public CommandType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public long getSenderId() {
        return senderId;
    }

    public Transaction getTransaction() {
        Gson gson = new Gson();
        return gson.fromJson(content, Transaction.class);
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static Command fromDataMessage(DataMessage dataMessage) {
        String json = new String(dataMessage.getContent());
        return fromJson(json);
    }

    public static Command fromJson(String json) {
        Gson gson = new Gson();
        // TODO: check if switch case is needed depending on command type
        return gson.fromJson(json, Command.class);
    }

    @Override
    public String toString() {
        return "Command{type=" + type + ", content='" + content + "', senderId=" + senderId + "}";
    }
}
