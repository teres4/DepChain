package pt.tecnico.sec.consensus;

public class TreeNode {

    // Tree and branches. Each command is wrapped in a node that additionally
    // contains a parent link which could be
    // a hash digest of the parent node. We omit the implementation details from the
    // pseudocode. During the protocol, a
    // replica delivers a message only after the branch led by the node is already
    // in its local tree. In practice, a recipient
    // who falls behind can catch up by fetching missing nodes from other replicas.
    // For brevity, these details are also
    // omitted from the pseudocode. Two branches are conflicting if neither one is
    // an extension of the other. Two nodes
    // are conflicting if the branches led by them are conflicting.

    // command
    // metadata
    // parent link (hash digest of parent node or reference to parent node)

    private String command; // TODO: define command structure
    private String metadata; // TODO: define metadata structure
    private TreeNode parent;
}
