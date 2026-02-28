package pt.tecnico.sec.consensus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.consensus.message.*;
import pt.tecnico.sec.utils.MemberManager;

public class Consensus {
    // current viewnumber
    private int viewNumber;

    // current leader ID
    private final long leaderID = 0;

    // my ID
    private final long replicaID;

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

    public enum Phase {
        NEW_VIEW, PREPARE, PRE_COMMIT, COMMIT, DECIDE
    };

    private Phase currentPhase;

    ExecutorService executorService = java.util.concurrent.Executors.newFixedThreadPool(10);

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
        if (amLeader()) {
            // act both as leader and replica

        }
        prepare();
        precommit();
        commit();
        decide();

    }

    public boolean amLeader() {
        return replicaID == leaderID;
    }

    // If ANYONE wants to start a new view, it sends a new view message to all
    // replicas and ONLY THE LEADER waits for (n-f) responses.
    public void startNewView() throws Exception {
        // send new view message to all replicas
        NewViewMessage newViewMessage = new NewViewMessage(viewNumber, QC, replicaID);
        broadcastMessage(newViewMessage);
    }

    // If it receives (n-f) new view messages, do stuff and move to prepare phase
    // TODO this should be in a separate thread that listens for incoming messages
    // and handles them ?
    public void onReceiveNewViewMessage(NewViewMessage newViewMessage) {
        // collects new view messages until it has (n-f) of them and chooses highest QC,
        // then moves to prepare phase

        int responsesReceived = 0;
        List<QuorumCertificate> receivedQCs = new ArrayList<>();

        while (responsesReceived < quorumSize) {
            // if (response != null && response.getPrepareQC() != null) {
            // receivedQCs.add(response.getPrepareQC());
            // responsesReceived++;
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

    public void newView() {

    }

    public void broadcastMessage(HotStuffMessage message) throws Exception {
        for (int i = 0; i < totalReplicas; i++) {
            if (i != replicaID) {
                authPerfLink.sendData(i, message);
            }
        }
    }

}
