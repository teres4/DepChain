package pt.tecnico.sec.consensus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.utils.MemberManager;

public class Consensus {
    // current viewnumber
    private int viewNumber;

    // current leader ID
    private final int leaderID = 0;

    // my ID
    private final int replicaID;

    // Quorum size
    private final int quorumSize;

    // total number of replicas
    private final int totalReplicas;

    // member management
    private final MemberManager memberManager;

    // message handling
    private final AuthPerfLink authPerfLink;

    // tree structure
    private final Tree tree;

    private QuorumCertificate QC; // highest QC known to this replica

    public Consensus(int replicaID, int totalReplicas, MemberManager memberManager, AuthPerfLink authPerfLink) {
        this.replicaID = replicaID;
        this.totalReplicas = totalReplicas;
        this.quorumSize = totalReplicas - (totalReplicas - 1) / 3; // n-f where f = (n-1)/3
        this.memberManager = memberManager;
        this.authPerfLink = authPerfLink;
        this.tree = new Tree();
    }

    // tree pending commands

    // highest node whose branch has been executed.

    public void consensusLoop() {
        while (true) {
            if (amLeader()) {
            }
            // send threads to send()
            // receive returns 
            // wait for messages and handle them accordingly
            //             HotStuffMessage msg = HotStuffMessage.fromDataMessage(dataMsg);

            // Option 1: Using getType()
            // HotStuffMessageType type = msg.getType();
            // switch (type) {
            //     case NEW_VIEW -> handleNewView((NewViewMessage) msg);
            //     case PREPARE -> handlePrepare((PrepareMessage) msg);
            //     case PREPARE_VOTE -> handlePrepareVote((PrepareVoteMessage) msg);
            //     case PRE_COMMIT -> handlePreCommit((PreCommitMessage) msg);
            //     case PRE_COMMIT_VOTE -> handlePreCommitVote((PreCommitVoteMessage) msg);
            //     case COMMIT -> handleCommit((CommitMessage) msg);
            //     case COMMIT_VOTE -> handleCommitVote((CommitVoteMessage) msg);
            //     case DECIDE -> handleDecide((DecideMessage) msg);
        }
    }

    public boolean amLeader() {
        return replicaID == leaderID;
    }

    // If replica wants to start a new view, it sends a new view message to all replicas and waits for (n-f) responses. 
    public void startNewView() {
        // send new view message to all replicas
        NewViewMessage newViewMessage = new NewViewMessage(viewNumber, QC, replicaID);
        // authPerfLink.broadcast(newViewMessage);
    }

    // If it receives (n-f) new view messages, do stuff and move to prepare phase
    // TODO this should be in a separate thread that listens for incoming messages and handles them ?
    public void onReceiveNewViewMessage(NewViewMessage newViewMessage) {
        // collects new view messages until it has (n-f) of them and chooses highest QC, then moves to prepare phase

        int responsesReceived = 0;
        List<QuorumCertificate> receivedQCs = new ArrayList<>();

        while (responsesReceived < quorumSize) {
            // if (response != null && response.getPrepareQC() != null) {
            //     receivedQCs.add(response.getPrepareQC());
            //     responsesReceived++;
            // }
        }
    
    }

    // prepare phase
    public void prepare() {

    }

    public void onReceivePrepareMessage(PrepareMessage prepareMessage) {
        
    }

    // pre-commit phase
    public void precommit() {

    }

    public void onReceivePreCommitMessage(PreCommitMessage preCommitMessage) {

    }

    // commit phase
    public void commit() {

    }

    public void onReceiveCommitMessage(CommitMessage commitMessage) {

    }

    // decide phase 
    public void decide() {

    }

    public void onReceiveDecideMessage(DecideMessage decideMessage) {

    }


}
