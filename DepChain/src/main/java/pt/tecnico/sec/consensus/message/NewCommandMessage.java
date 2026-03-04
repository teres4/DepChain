package pt.tecnico.sec.consensus.message;

public class NewCommandMessage extends HotStuffMessage {

    private final String command;

    public NewCommandMessage(int viewNumber, long senderId, String command) {
        super(HotStuffMessageType.NEW_COMMAND, viewNumber, senderId);
        this.command = command;
    }

    public String getCommand() {
        return command;
    }

    @Override
    public String toString() {
        return "NewCommandMessage{command=" + command + ", view=" + getViewNumber()
                + ", sender=" + getSenderId() + "}";
    }
}