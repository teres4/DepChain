package pt.tecnico.sec.consensus;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.utils.MemberManager;

public class consensus {
    // current viewnumber
    private int viewNumber;

    // current leader ID
    private int leaderID = 0;

    // my ID
    private int replicaID;

    // Quorum size
    private int quorumSize;

    // member management
    private MemberManager memberManager;

    // message handling
    private AuthPerfLink authPerfLink;

    // tree structure
    private Tree tree;

    // tree pending commands

    // highest node whose branch has been executed.

    public boolean amLeader() {
        return replicaID == leaderID;
    }

}
