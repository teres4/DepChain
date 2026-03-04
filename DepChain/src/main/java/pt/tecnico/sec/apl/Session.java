package pt.tecnico.sec.apl;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.crypto.SecretKey;

import pt.tecnico.sec.apl.message.DataMessage;

public class Session {

    private final long senderID; // replica ID of the other party in the session

    // Go-Back-N sender state
    private long sendBase; // oldest unacknowledged packet
    private long nextSeqNum; // next sequence number to use
    private final int windowSize; // N in Go-Back-N
    private final Map<Long, DataMessage> sendBuffer; // buffer for unacknowledged packets
    private Timer timer; // retransmission timer
    private final long timeout; // timeout in milliseconds

    // Go-Back-N receiver state
    private long expectedSeqNum; // next expected sequence number

    private SecretKey sessionKey;

    private final InetSocketAddress addr;

    Session(long senderID, long sequenceNumber, SecretKey sessionKey, InetSocketAddress addr) {
        this(senderID, sequenceNumber, sessionKey, addr, 10, 5000); // default window size=10, timeout=5s
    }

    Session(long senderID, long sequenceNumber, SecretKey sessionKey, InetSocketAddress addr, int windowSize,
            long timeout) {
        this.senderID = senderID;
        this.sendBase = sequenceNumber;
        this.nextSeqNum = sequenceNumber;
        this.expectedSeqNum = sequenceNumber + 1; // receiver expects first data packet
        this.sessionKey = sessionKey;
        this.addr = addr;
        this.windowSize = windowSize;
        this.timeout = timeout;
        this.sendBuffer = new LinkedHashMap<>();
        this.timer = null;
    }

    // Sender methods

    public synchronized boolean canSend() {
        return this.nextSeqNum < this.sendBase + this.windowSize;
    }

    public synchronized long getNextSequenceNumber() {
        return this.nextSeqNum;
    }

    public synchronized void advanceNextSeqNum() {
        this.nextSeqNum++;
    }

    public synchronized void bufferPacket(long seqNum, DataMessage msg, Runnable timeoutHandler) {
        sendBuffer.put(seqNum, msg);

        // Start timer only for the first unacknowledged packet (window base)
        if (seqNum == this.sendBase && this.timer == null) {
            startTimer(timeoutHandler);
        }
    }

    public synchronized void handleAck(long ackNum) {
        // Cumulative ACK: ackNum acknowledges all packets up to and including ackNum-1
        if (ackNum > this.sendBase) {
            // Remove acknowledged packets from buffer
            for (long i = this.sendBase; i < ackNum; i++) {
                sendBuffer.remove(i);
            }
            this.sendBase = ackNum;

            if (this.sendBase == this.nextSeqNum) {
                // no need to retransmit, stop timer
                stopTimer();
            } else {
                // need to retransmit, restart timer for the new window base
                restartTimer();
            }
        }
    }

    public synchronized Map<Long, DataMessage> getUnacknowledgedPackets() {
        return new LinkedHashMap<>(this.sendBuffer);
    }

    public synchronized boolean hasUnacknowledgedPackets() {
        return !sendBuffer.isEmpty();
    }

    /**
     * Restart the timer manually (e.g., after retransmission).
     * Only works if there are unacknowledged packets and a timeout handler is set.
     */
    public synchronized void restartTimerIfNeeded() {
        if (hasUnacknowledgedPackets() && this.currentTimeoutHandler != null) {
            restartTimer();
        }
    }

    private Runnable currentTimeoutHandler;

    private synchronized void startTimer(Runnable timeoutHandler) {
        if (this.timer != null) {
            timer.cancel();
        }
        this.timer = new Timer();
        this.currentTimeoutHandler = timeoutHandler;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                timeoutHandler.run();
            }
        }, this.timeout);
    }

    private synchronized void stopTimer() {
        if (this.timer != null) {
            timer.cancel();
            this.timer = null;
            this.currentTimeoutHandler = null;
        }
    }

    private synchronized void restartTimer() {
        if (this.currentTimeoutHandler != null) {
            stopTimer();
            startTimer(this.currentTimeoutHandler);
        }
    }

    // Receiver methods

    public synchronized long getExpectedSequenceNumber() {
        return this.expectedSeqNum;
    }

    public synchronized void setExpectedSequenceNumber(long seqNum) {
        this.expectedSeqNum = seqNum;
    }

    // Initialization and compatibility methods

    /**
     * Get the last acknowledged sequence number.
     * Used for connection handshake and monitoring.
     */
    public long getSequenceNumber() {
        return Math.max(this.sendBase - 1, this.expectedSeqNum - 1);
    }

    /**
     * Initialize session state after handshake or reset.
     * Sets both sender and receiver to be ready for data transfer.
     * Use this only during connection establishment.
     */
    public synchronized void setSequenceNumber(long sequenceNumber) {
        // Update both sender and receiver state
        this.sendBase = sequenceNumber;
        this.nextSeqNum = sequenceNumber;
        this.expectedSeqNum = sequenceNumber + 1;
    }

    @Deprecated
    public synchronized boolean isWaitingForReply() {
        return this.sendBase < this.nextSeqNum; // have unacknowledged packets
    }

    @Deprecated
    public synchronized void replyReceived() {
        // For backward compatibility - treat as ACK for next packet
        handleAck(this.sendBase + 1);
    }

    public SecretKey getSessionKey() {
        return this.sessionKey;
    }

    public void setSessionKey(SecretKey sessionKey) {
        this.sessionKey = sessionKey;
    }

    public InetSocketAddress getAddr() {
        return this.addr;
    }

    public long getSenderID() {
        return this.senderID;
    }

    public int getWindowSize() {
        return this.windowSize;
    }

    public synchronized void cleanup() {
        stopTimer();
        sendBuffer.clear();
    }
}
