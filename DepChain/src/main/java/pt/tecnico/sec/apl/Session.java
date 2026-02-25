package pt.tecnico.sec.apl;

import javax.crypto.SecretKey;

public class Session {

    private long senderID;
    private long sequenceNumber;
    private SecretKey sessionKey;
    private long lastSeen;

    final int TIMEOUT = 5000; // 5 second timeout for now

    Session(long senderID, long sequenceNumber, SecretKey sessionKey) {
        this.senderID = senderID;
        this.sequenceNumber = sequenceNumber;
        this.sessionKey = sessionKey;
    }

    public String getSequenceNumber() {
        return Long.toString(sequenceNumber);
    }

    public SecretKey getSessionKey() {
        return sessionKey;
    }

    public void touch() {
        lastSeen = System.currentTimeMillis();
    }

}
