package pt.tecnico.sec.apl;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

public class Session {

    private final long senderID; // replica ID of the other party in the session
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

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public SecretKey getSessionKey() {
        return sessionKey;
    }

    public synchronized boolean waitForReply() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;

        while (!replyReceived) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                System.out.println("Timeout waiting for reply, retransmitting...");
                return false;
            }
            wait(remaining);
        }

        replyReceived = false; // reset for next message
        return true;
    }

    public synchronized void replyReceived() {
        this.replyReceived = true;
        notifyAll();
    }

    public InetSocketAddress getAddr() {
        return addr;
    }

    public long getSenderID() {
        return senderID;
    }

}
