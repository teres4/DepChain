package pt.tecnico.sec.apl;

import java.net.InetSocketAddress;
import java.sql.Time;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

import javax.crypto.SecretKey;
import javax.xml.crypto.Data;

import pt.tecnico.sec.apl.message.DataMessage;
import pt.tecnico.sec.utils.DHKeyAgreement;

public class Session {

    private final long senderID; // replica ID of the other party in the session

    // Selective Repeat sender state
    private long sendBase; // oldest unacknowledged packet
    private long nextSeqNum; // next sequence number to use
    private final int windowSize; // size of the sender's window
    private final Map<Long, DataMessage> sendBuffer; // buffer for unacknowledged packets
    private final Map<Long, DataMessage> receiveBuffer = new TreeMap<>(); // buffer for out-of-order received packets
    private final Map<Long, Timer> sendTimers = new TreeMap<>(); // timers for received packets (for ACKs)
    private final Map<Long, Runnable> timeoutHandlers = new TreeMap<>(); // timeout handlers for each sequence number
    private final long timeout; // timeout in milliseconds

    private long expectedSeqNum; // last in-order received packet + 1 (the next expected sequence number)

    private volatile SecretKey sessionKey;
    private volatile DHKeyAgreement ephemeralDH; // per-session ephemeral DH key pair

    private boolean connectionEstablished = false; // track if handshake is complete

    private final InetSocketAddress addr;

    private boolean isClientSession;

    Session(long senderID, long sequenceNumber, SecretKey sessionKey, InetSocketAddress addr, DHKeyAgreement dh,
            boolean isClient) {
        this(senderID, sequenceNumber, sessionKey, addr, dh, 10, 5000, isClient); // default window size=10, timeout=5s
    }

    Session(long senderID, long sequenceNumber, SecretKey sessionKey, InetSocketAddress addr, DHKeyAgreement dh,
            int windowSize,
            long timeout, boolean isClient) {
        this.senderID = senderID;
        this.sendBase = sequenceNumber;
        this.nextSeqNum = sequenceNumber;
        this.expectedSeqNum = sequenceNumber + 1; // receiver expects first data packet
        this.sessionKey = sessionKey;
        this.addr = addr;
        this.ephemeralDH = dh;
        this.windowSize = windowSize;
        this.timeout = timeout;
        this.sendBuffer = new TreeMap<>();
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
        sendBuffer.put(seqNum, msg);

        // Store the timeout handler for this sequence number
        if (timeoutHandler != null) {
            timeoutHandlers.put(seqNum, timeoutHandler);
        } else {
            System.err.println("Warning: Null timeout handler for seqNum " + seqNum);
        }

        // Start timer for this specific packet
        stopTimer(seqNum);
        startTimer(seqNum);
    }

    public synchronized void handleAck(long ackNum) {
        // ACKnowleges only 1 packet (comulative ack was in phase 1)
        if (ackNum > this.sendBase) {
            // remove acknowleged packet from buffer
            sendBuffer.remove(ackNum);
            if (ackNum == this.sendBase) {
                // Move sendBase forward to the next unacknowledged packet
                while (!sendBuffer.containsKey(this.sendBase) && this.sendBase < this.nextSeqNum) {
                    this.sendBase++;
                }
            }

            // stop timer
            stopTimer(ackNum);
        }
    }

    public synchronized Map<Long, DataMessage> getUnacknowledgedPackets() {
        return new TreeMap<>(this.sendBuffer);
    }

    public synchronized boolean hasUnacknowledgedPackets() {
        return !sendBuffer.isEmpty();
    }

    /**
     * Restart the timer for a specific sequence number after retransmission
     * Used in Selective Repeat when retransmitting a specific timed-out packet
     */
    public synchronized void restartTimerIfNeeded(long seqNum) {
        if (sendBuffer.containsKey(seqNum) && timeoutHandlers.containsKey(seqNum)) {
            restartTimer(seqNum);
        }
    }

    private synchronized void startTimer(Long ackNum) {
        stopTimer(ackNum); // Ensure no existing timer for this ackNum
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (Session.this) {
                    if (sendBuffer.containsKey(ackNum)) {
                        System.out.println("\u001B[31m[Session " + senderID + "] Timeout for seqNum=" + ackNum
                                + ", retransmitting...\u001B[0m");
                        // Call the timeout handler for this sequence number
                        Runnable handler = timeoutHandlers.get(ackNum);
                        if (handler != null) {
                            handler.run();
                        } else {
                            System.err.println("Warning: No timeout handler found for seqNum=" + ackNum);
                        }
                    }
                }
            }
        }, timeout);
        sendTimers.put(ackNum, timer);
    }

    private synchronized void stopTimer(Long ackNum) {
        Timer timer = sendTimers.remove(ackNum);
        if (timer != null) {
            timer.cancel();
        }
    }

    private synchronized void restartTimer(Long ackNum) {
        Timer timer = sendTimers.get(ackNum);
        if (timer != null) {
            timer.cancel();
            // Retrieve and use the stored timeout handler for this packet
            startTimer(ackNum);
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

    public DHKeyAgreement getEphemeralDH() {
        return this.ephemeralDH;
    }

    public void setEphemeralDH(DHKeyAgreement dh) {
        this.ephemeralDH = dh;
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
        for (Timer timer : sendTimers.values()) {
            timer.cancel();
        }
        sendTimers.clear();
        timeoutHandlers.clear();
        sendBuffer.clear();
        receiveBuffer.clear();
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

    /**
     * Important function for Selective repeat
     * Returns a list of ordered deliverable packets when receiving a new packet
     * IMPORTANT: this function updates expectedseqnumber
     * 
     * @param msg
     * @return list of in-order packets (previously processed and verified) that can
     *         be delivered to the application
     *         layer
     */
    public synchronized List<DataMessage> addReceivedPacket(DataMessage msg) {
        long seqNum = msg.getSequenceNumber();
        List<DataMessage> deliverable = new java.util.ArrayList<>();

        // If it's an old packet (already delivered), discard it
        if (seqNum < expectedSeqNum) {
            return deliverable; // empty list
        }

        // If it's within the receiving window
        if (seqNum >= expectedSeqNum && seqNum < expectedSeqNum + windowSize) {
            // Add to receive buffer
            receiveBuffer.put(seqNum, msg);

            // Check if we can deliver any in-order packets starting from expectedSeqNum
            while (receiveBuffer.containsKey(expectedSeqNum)) {
                deliverable.add(receiveBuffer.remove(expectedSeqNum));
                expectedSeqNum++;
            }

            return deliverable;
        }

        // If it's beyond the window, drop it
        return deliverable;
    }
}
