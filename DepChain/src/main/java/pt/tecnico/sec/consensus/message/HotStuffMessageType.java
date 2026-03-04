package pt.tecnico.sec.consensus.message;

public enum HotStuffMessageType {
    NEW_COMMAND,
    NEW_VIEW,
    PREPARE,
    PREPARE_VOTE,
    PRE_COMMIT,
    PRE_COMMIT_VOTE,
    COMMIT,
    COMMIT_VOTE,
    DECIDE
}