package pt.tecnico.sec.apl;

import java.net.InetSocketAddress;

import javax.crypto.SecretKey;

public class Session {

    private long senderID;
    private long sequenceNumber;
    private SecretKey sessionKey;
    private boolean replyReceived = false;

    private final InetSocketAddress addr;

    final int TIMEOUT = 5000; // 5 second timeout for now

    Session(long senderID, long sequenceNumber, SecretKey sessionKey, InetSocketAddress addr) {
        this.senderID = senderID;
        this.sequenceNumber = sequenceNumber;
        this.sessionKey = sessionKey;
        this.addr = addr;
    }

    public String getSequenceNumber() {
        return Long.toString(sequenceNumber);
    }

    public SecretKey getSessionKey() {
        return sessionKey;
    }

    public synchronized boolean waitForReply() {
        // wait for ack with timeout
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < TIMEOUT) {
            if (replyReceived) {
                replyReceived = false; // reset for next message
                return true;
            }
        }
        // retransmit message
        return false;
    }

    public synchronized void replyReceived() {
        this.replyReceived = true;
    }

    public InetSocketAddress getAddr() {
        return addr;
    }

}
