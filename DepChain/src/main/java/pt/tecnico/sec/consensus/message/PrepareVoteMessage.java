package pt.tecnico.sec.consensus.message;

import pt.tecnico.sec.consensus.Node;

// sent by a replica to leader after accepting the PREPARE message
public class PrepareVoteMessage extends HotStuffMessage {
    private final Node node; // used by the leader to verify the vote is for the correct block
    private final String partialSig; // something to do with threshold signatures, still need to figure out

    public PrepareVoteMessage(int viewNumber, Node node, long senderId) {
        super(HotStuffMessageType.PREPARE_VOTE, viewNumber, senderId);
        this.node = node;
        this.partialSig = null;
    }

    public Node getNode() {
        return node;
    }

    public String getPartialSig() {
        return partialSig;
    }

    @Override
    public String toString() {
        return "PrepareVoteMessage{view=" + getViewNumber() + ", sender=" + getSenderId() +
                ", node=" + node + "}";
    }
}