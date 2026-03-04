package pt.tecnico.sec.consensus.message;

import com.google.gson.Gson;

import pt.tecnico.sec.consensus.QuorumCertificate;

// sent by a replica as it transitions into a newView viewNumber
// carries the highest prepareQC that said replica has seen (null if none, e.g. on startup)
public class NewViewMessage extends HotStuffMessage {
    private final QuorumCertificate prepareQC; // null if replica has never seen a prepareQC

    public NewViewMessage(int viewNumber, QuorumCertificate prepareQC, long senderId) {
        super(HotStuffMessageType.NEW_VIEW, viewNumber, senderId);
        this.prepareQC = prepareQC;
    }

    public QuorumCertificate getPrepareQC() {
        return prepareQC;
    }

    @Override
    public String toString() {
        return "NewViewMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
                ", prepareQC=" + prepareQC + "}";
    }

    public static NewViewMessage fromJson(String json) {
        return new Gson().fromJson(json, NewViewMessage.class);
    }
}