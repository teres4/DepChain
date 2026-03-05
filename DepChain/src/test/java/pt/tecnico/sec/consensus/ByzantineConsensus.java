package pt.tecnico.sec.consensus;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.message.PrepareMessage;
import pt.tecnico.sec.consensus.message.PrepareVoteMessage;
import pt.tecnico.sec.replica.ReplicaState;
import pt.tecnico.sec.utils.MemberManager;

public class ByzantineConsensus extends Consensus {

    public enum ByzantineType {
        SILENT,              // never sends anything
        DOUBLE_VOTE,         // votes for two different values in same phase
        WRONG_SIGNATURE,     // sends votes with invalid signatures
        WRONG_NODE_HASH,     // votes for a different node than proposed
        REPLAY_OLD_MESSAGES, // replays messages from previous views
        FORGED_QC,           // creates fake QCs
        INVALID_PROPOSAL,    // leader proposes conflicting blocks
    }

    private final ByzantineType type;

    public ByzantineConsensus(long replicaID, int totalReplicas,
                               MemberManager mm, AuthPerfLink link,
                               ReplicaState replicaState, ByzantineType type) {
        super(replicaID, totalReplicas, mm, link, replicaState);
        this.type = type;
    }

    @Override
    public void onReceivePrepareMessage(PrepareMessage msg) {
        switch (type) {
            case SILENT -> { /* do nothing */ }

            case DOUBLE_VOTE -> {
                // Vote for the real node AND a fake one
                super.onReceivePrepareMessage(msg);
                sendFakeVoteForDifferentNode(msg);
            }

            case WRONG_SIGNATURE -> {
                // Send vote with garbage signature
                PrepareVoteMessage fakeVote = new PrepareVoteMessage(
                    msg.getViewNumber(), msg.getNode(),
                    this.replicaID, "invalidsignature=="
                );
                authPerfLink.sendData(getLeader(msg.getViewNumber()), fakeVote);
            }

            case WRONG_NODE_HASH -> {
                // Vote for a completely different node hash
                PrepareVoteMessage fakeVote = new PrepareVoteMessage(
                    msg.getViewNumber(), createFakeNode(),
                    this.replicaID, tsign(HotStuffMessageType.PREPARE_VOTE,
                        msg.getViewNumber(), "fakehash123")
                );
                authPerfLink.sendData(getLeader(msg.getViewNumber()), fakeVote);
            }

            default -> super.onReceivePrepareMessage(msg);
        }
    }

    @Override
    public void prepare(QuorumCertificate highQC) throws Exception {
        if (type == ByzantineType.INVALID_PROPOSAL) {
            // Send different proposals to different replicas
            sendConflictingProposals(highQC);
        } else {
            super.prepare(highQC);
        }
    }
}