package pt.tecnico.sec.apl;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

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

    private volatile SecretKey sessionKey;

    private boolean connectionEstablished = false; // track if handshake is complete

    private final InetSocketAddress addr;

    private boolean isClientSession;

    Session(long senderID, long sequenceNumber, SecretKey sessionKey, InetSocketAddress addr, boolean isClient) {
        this(senderID, sequenceNumber, sessionKey, addr, 10, 5000, isClient); // default window size=10, timeout=5s
    }

    Session(long senderID, long sequenceNumber, SecretKey sessionKey, InetSocketAddress addr, int windowSize,
            long timeout, boolean isClient) {
        this.senderID = senderID;
        this.sendBase = sequenceNumber;
        this.nextSeqNum = sequenceNumber;
        this.expectedSeqNum = sequenceNumber + 1; // receiver expects first data packet
        this.sessionKey = sessionKey;
        this.addr = addr;
        this.windowSize = windowSize;
        this.timeout = timeout;
        this.sendBuffer = new TreeMap<>();
        this.timer = null;
        this.isClientSession = isClient;
    }

    // Sender methods

    public synchronized boolean canSend() {
        return this.sendBuffer.size() < this.windowSize;
    }

    public synchronized long getSendBase() {
        return this.sendBase;
    }

    public synchronized long getNextSequenceNumber() {
        return this.nextSeqNum;
    }

    public synchronized void advanceNextSeqNum() {
        this.nextSeqNum++;
    }

    public boolean isClientSession() {
        return this.isClientSession;
    }

    public synchronized void bufferPacket(long seqNum, DataMessage msg, Runnable timeoutHandler) {
        boolean wasEmpty = sendBuffer.isEmpty();
        sendBuffer.put(seqNum, msg);

        // Start timer when the first packet enters an empty buffer
        if (wasEmpty) {
            if (timeoutHandler != null) {
                stopTimer(); // clear any stale timer
                startTimer(timeoutHandler);
            } else {
                System.err.println("Warning: Cannot start timer, timeout handler is null");
            }
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

            if (sendBuffer.isEmpty()) {
                stopTimer();
            } else {
                // Still have unacknowledged packets, restart timer
                if (this.currentTimeoutHandler != null) {
                    restartTimer();
                }
            }
        }
    }

    public synchronized Map<Long, DataMessage> getUnacknowledgedPackets() {
        return new TreeMap<>(this.sendBuffer);
    }

    public synchronized boolean hasUnacknowledgedPackets() {
        return !sendBuffer.isEmpty();
    }

    /**
     * Restart the timer on a retransmission
     * Only works if there are unacknowledged packets and a timeout handler is set.
     */
    public synchronized void restartTimerIfNeeded() {
        if (hasUnacknowledgedPackets() && this.currentTimeoutHandler != null) {
            restartTimer();
        }
    }

    private Runnable currentTimeoutHandler;

    private synchronized void startTimer(Runnable timeoutHandler) {
        if (timeoutHandler == null) {
            System.err.println("Warning: Attempted to start timer with null handler");
            return;
        }

        if (this.timer != null) {
            timer.cancel();
        }
        this.timer = new Timer();
        this.currentTimeoutHandler = timeoutHandler;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Defensive check in case handler was cleared between schedule and fire
                if (timeoutHandler != null) {
                    timeoutHandler.run();
                }
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
            Runnable handler = this.currentTimeoutHandler; // Save handler before stopping
            stopTimer();
            startTimer(handler); // Use saved handler
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

    public long getSequenceNumber() {
        return Math.max(this.sendBase - 1, this.expectedSeqNum - 1);
    }

    public synchronized void setSequenceNumber(long sequenceNumber) {
        // Update both sender and receiver state
        this.sendBase = sequenceNumber;
        this.nextSeqNum = sequenceNumber + 1; // Next packet to send starts at sequenceNumber + 1
        this.expectedSeqNum = sequenceNumber + 1; // Expected to receive starts at sequenceNumber + 1
        System.out.println("\u001B[36m[Session " + senderID + "] setSequenceNumber(" + sequenceNumber + ") -> sendBase="
                + sendBase + ", nextSeqNum=" + nextSeqNum + ", expectedSeqNum=" + expectedSeqNum + "\u001B[0m");
    }

    public synchronized long getAndAdvanceSequenceNumber() {
        long seqNum = this.nextSeqNum;
        this.nextSeqNum++;
        return seqNum;
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

    public synchronized boolean isConnectionEstablished() {
        return connectionEstablished;
    }

    public synchronized boolean waitForConnectionEstablished() {
        long startTime = System.currentTimeMillis();
        while (!connectionEstablished) {
            try {
                wait(100); // Wait with timeout to allow periodic checks
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false; // Interrupted while waiting
            }

            if (System.currentTimeMillis() - startTime > 30000) { // 30 seconds timeout
                System.err.println("Timeout waiting for connection to be established");
                return false;
            }
        }
        return true; // Connection established
    }

    public synchronized void markConnectionEstablished() {
        System.out.println("Connection established with senderID=" + senderID);
        this.connectionEstablished = true;
        notifyAll();
    }
}
