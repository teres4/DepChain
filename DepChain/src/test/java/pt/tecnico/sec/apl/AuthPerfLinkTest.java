package pt.tecnico.sec.apl;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import pt.tecnico.sec.apl.message.DataMessage;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;
import pt.tecnico.sec.utils.MemberManager;

public class AuthPerfLinkTest {

    private AuthPerfLink sender;
    private AuthPerfLink receiver;
    private DatagramSocket senderSocket;
    private DatagramSocket receiverSocket;
    private ExecutorService senderExecutor;
    private ExecutorService receiverExecutor;
    private SessionManager senderSessionManager;
    private SessionManager receiverSessionManager;
    private MemberManager senderMemberManager;
    private MemberManager receiverMemberManager;
    private KeyPair senderKeyPair;
    private KeyPair receiverKeyPair;

    private static final long SENDER_ID = 100;
    private static final long RECEIVER_ID = 102;
    private static final int SENDER_PORT = 9001;
    private static final int RECEIVER_PORT = 9002;

    @BeforeEach
    public void setup() throws Exception {
        // Generate key pairs for testing
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        senderKeyPair = keyGen.generateKeyPair();
        receiverKeyPair = keyGen.generateKeyPair();

        // Create sockets
        senderSocket = new DatagramSocket(SENDER_PORT);
        receiverSocket = new DatagramSocket(RECEIVER_PORT);

        // Create executors
        senderExecutor = Executors.newFixedThreadPool(20);
        receiverExecutor = Executors.newFixedThreadPool(20);

        // Create session managers
        senderSessionManager = new SessionManager();
        receiverSessionManager = new SessionManager();

        // Create member managers
        senderMemberManager = createMemberManager(RECEIVER_ID, RECEIVER_PORT,
                receiverKeyPair.getPublic());
        receiverMemberManager = createMemberManager(SENDER_ID, SENDER_PORT,
                senderKeyPair.getPublic());

        // Create AuthPerfLink instances
        sender = new AuthPerfLink(SENDER_ID, senderKeyPair.getPrivate(), true, senderSocket,
                senderExecutor, senderSessionManager, senderMemberManager);

        receiver = new AuthPerfLink(RECEIVER_ID, receiverKeyPair.getPrivate(), true, receiverSocket,
                receiverExecutor, receiverSessionManager, receiverMemberManager);

        // Start receive threads
        new Thread(() -> sender.receive()).start();
        new Thread(() -> receiver.receive()).start();
    }

    @AfterEach
    public void teardown() {
        if (sender != null)
            sender.shutdown();
        if (receiver != null)
            receiver.shutdown();
    }

    private MemberManager createMemberManager(long otherId, int otherPort,
            PublicKey otherPublicKey) {
        MemberManager manager = mock(MemberManager.class);
        // Use IP address directly (not hostname) to match what DatagramPacket returns.
        // DatagramPacket.getAddress() returns a raw InetAddress without a hostname,
        // so InetSocketAddress("localhost", port) won't .equals() InetSocketAddress(ip,
        // port).
        InetSocketAddress otherAddr = new InetSocketAddress("localhost", otherPort);
        MemberInfo otherMember = new MemberInfo(otherId, otherPublicKey, "test-key-" + otherId, otherAddr);

        // Mock the member manager to return our test member
        when(manager.getMember(otherId)).thenReturn(otherMember);
        when(manager.getMemberByAddress(otherAddr)).thenReturn(otherMember);
        when(manager.getMembers()).thenReturn(new MemberInfo[] { otherMember });

        return manager;
    }

    // Test bidirection communication
    @Test
    @Timeout(15)
    public void testBidirectionalCommunication() throws Exception {
        CountDownLatch senderLatch = new CountDownLatch(1);
        CountDownLatch receiverLatch = new CountDownLatch(1);

        sender.setConsensusMessageListener(content -> senderLatch.countDown());
        receiver.setConsensusMessageListener(content -> receiverLatch.countDown());

        // Establish connections
        Session senderSession = sender.startConnection(RECEIVER_ID);
        Session receiverSession = receiver.startConnection(SENDER_ID);

        assertNotNull(senderSession);
        assertNotNull(receiverSession);
        Thread.sleep(500);

        // Send in both directions
        String toReceiver = "Sender to Receiver";
        String toSender = "Receiver to Sender";
        sender.sendData(RECEIVER_ID, toReceiver.getBytes());
        receiver.sendData(SENDER_ID, toSender.getBytes());

        // Both should receive
        assertTrue(receiverLatch.await(5, TimeUnit.SECONDS),
                "Receiver should get message");
        assertTrue(senderLatch.await(5, TimeUnit.SECONDS),
                "Sender should get message");
    }

    // Regression test for intermittent handshake race:
    // both peers start connection at the same time, then exchange authenticated
    // data.
    @RepeatedTest(15)
    @Timeout(20)
    public void testSimultaneousStartConnection_DoesNotBreakSessionKeyOrHmac() throws Exception {
        CountDownLatch senderLatch = new CountDownLatch(1);
        CountDownLatch receiverLatch = new CountDownLatch(1);

        sender.setConsensusMessageListener(content -> senderLatch.countDown());
        receiver.setConsensusMessageListener(content -> receiverLatch.countDown());

        ExecutorService racePool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Session> senderConnect = CompletableFuture.supplyAsync(
                    () -> sender.startConnection(RECEIVER_ID),
                    racePool);
            CompletableFuture<Session> receiverConnect = CompletableFuture
                    .supplyAsync(() -> receiver.startConnection(SENDER_ID), racePool);

            Session senderSession = senderConnect.get(8, TimeUnit.SECONDS);
            Session receiverSession = receiverConnect.get(8, TimeUnit.SECONDS);

            assertNotNull(senderSession, "Sender session must be established");
            assertNotNull(receiverSession, "Receiver session must be established");
            assertTrue(senderSession.isConnectionEstablished(), "Sender session should be marked established");
            assertTrue(receiverSession.isConnectionEstablished(), "Receiver session should be marked established");

            // Verify both sides converged to the same shared key material.
            Session senderViewOnReceiver = receiver.getSession(SENDER_ID);
            Session receiverViewOnSender = sender.getSession(RECEIVER_ID);
            assertNotNull(senderViewOnReceiver, "Receiver must have session for sender");
            assertNotNull(receiverViewOnSender, "Sender must have session for receiver");
            assertNotNull(senderViewOnReceiver.getSessionKey(), "Receiver-side session key must be set");
            assertNotNull(receiverViewOnSender.getSessionKey(), "Sender-side session key must be set");
            assertArrayEquals(senderViewOnReceiver.getSessionKey().getEncoded(),
                    receiverViewOnSender.getSessionKey().getEncoded(),
                    "Both peers must derive the same session key after simultaneous connect");

            // Exchange messages in both directions; HMAC verification must succeed.
            sender.sendData(RECEIVER_ID, "race-msg-sender".getBytes());
            receiver.sendData(SENDER_ID, "race-msg-receiver".getBytes());

            assertTrue(receiverLatch.await(5, TimeUnit.SECONDS),
                    "Receiver should receive sender message after race handshake");
            assertTrue(senderLatch.await(5, TimeUnit.SECONDS),
                    "Sender should receive receiver message after race handshake");
        } finally {
            racePool.shutdownNow();
            racePool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    // A message sent from a correct sender should be delivered to a correct
    // receiver
    @Test
    @Timeout(10)
    public void testReliableDelivery_SingleMessage() throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();

        // listener will be triggered whenever the receiver gets a message
        receiver.setConsensusMessageListener(content -> future.complete(content));

        // Establish connection first
        Session session = sender.startConnection(RECEIVER_ID);
        assertNotNull(session, "Connection should be established");
        Thread.sleep(500); // Allow handshake to complete

        // Send message
        String testMessage = "Hello, World!";
        sender.sendData(RECEIVER_ID, testMessage.getBytes());

        // Wait for message to be received
        String receivedMessage = future.get(5, TimeUnit.SECONDS);
        assertNotNull(receivedMessage, "Message should be delivered");
        assertTrue(receivedMessage.contains("Hello, World!"),
                "Received message should contain the sent content");
    }

    // Test that multiple messages sent in sequence are all delivered
    @Test
    @Timeout(60)
    public void testReliableDelivery_MultipleMessages() throws Exception {
        int messageCount = 10;
        CompletableFuture<Void> allMessagesReceived = new CompletableFuture<>();
        List<String> receivedMessages = new ArrayList<>();

        receiver.setConsensusMessageListener(content -> {
            synchronized (receivedMessages) {
                receivedMessages.add(content);
                System.out.println("Received message: " + content); // Debug log

                if (receivedMessages.size() == messageCount) {
                    allMessagesReceived.complete(null);
                }
            }
        });

        // Establish connection
        Session session = sender.startConnection(RECEIVER_ID);
        assertNotNull(session);
        Thread.sleep(500);

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            System.out.println("Sending message: Message " + i); // Debug log
            sender.sendData(RECEIVER_ID, ("Message " + i).getBytes());
        }

        // Wait for all messages
        allMessagesReceived.get(60, TimeUnit.SECONDS);
        assertEquals(messageCount, receivedMessages.size(),
                "Should receive exactly " + messageCount + " messages");
    }

    // Tests that sending more messages than the window size works correctly
    // (proves ACK processing and window advancement work end-to-end)
    @Test
    @Timeout(60)
    public void testReliableDelivery_ExceedWindowSize() throws Exception {
        // Window size is 10 by default, send 25 messages to force multiple
        // rounds of window fill -> ACK -> advance -> fill
        int messageCount = 25;
        CompletableFuture<Void> allMessagesDelivered = new CompletableFuture<>();
        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

        receiver.setConsensusMessageListener(content -> {
            receivedMessages.add(content);

            if (receivedMessages.size() == messageCount) {
                allMessagesDelivered.complete(null);
            }
        });

        // Establish connection
        Session session = sender.startConnection(RECEIVER_ID);
        assertNotNull(session);
        Thread.sleep(500);

        // Send more messages than window size
        for (int i = 0; i < messageCount; i++) {
            sender.sendData(RECEIVER_ID, ("Message " + i).getBytes());
        }

        // All messages should eventually be delivered
        allMessagesDelivered.get(30, TimeUnit.SECONDS);

        assertEquals(messageCount, receivedMessages.size(),
                "All " + messageCount + " messages should be delivered");

        // Verify order is preserved
        for (int i = 0; i < messageCount; i++) {
            assertTrue(receivedMessages.get(i).contains("Message " + i),
                    "Message " + i + " should be at position " + i);
        }
    }

    // Test Selective Repeat: Window management allows many more messages than
    // Go-Back-N
    // This indirectly tests that Selective Repeat is working because individual
    // packets can be ACKed out of order without blocking the sender's window
    @Test
    @Timeout(30)
    public void testSelectiveRepeat_WindowManagement() throws Exception {
        // This test is equivalent to testReliableDelivery_ExceedWindowSize
        // but emphasizes that in Selective Repeat, out-of-order ACKs unblock the window
        int messageCount = 50; // Send double the window size
        CompletableFuture<Void> allMessagesDelivered = new CompletableFuture<>();
        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

        receiver.setConsensusMessageListener(content -> {
            receivedMessages.add(content);
            if (receivedMessages.size() == messageCount) {
                allMessagesDelivered.complete(null);
            }
        });

        // Establish connection
        Session session = sender.startConnection(RECEIVER_ID);
        assertNotNull(session);
        Thread.sleep(500);

        // Send many messages - with Selective Repeat, window advances with individual
        // ACKs
        for (int i = 0; i < messageCount; i++) {
            sender.sendData(RECEIVER_ID, ("Message " + i).getBytes());
        }

        // All messages should be delivered in order
        allMessagesDelivered.get(20, TimeUnit.SECONDS);
        assertEquals(messageCount, receivedMessages.size(),
                "All " + messageCount + " messages should be delivered");

        // Verify order is preserved
        for (int i = 0; i < messageCount; i++) {
            assertTrue(receivedMessages.get(i).contains("Message " + i),
                    "Message " + i + " should be at position " + i);
        }
    }

    // Test Selective Repeat: Session receives packets correctly
    // Tests that addReceivedPacket returns empty list for duplicates
    @Test
    @Timeout(10)
    public void testSelectiveRepeat_ReceiveBufferManagement() throws Exception {
        // Establish connection between sender and receiver
        Session session = sender.startConnection(RECEIVER_ID);
        assertNotNull(session);
        Thread.sleep(500);

        Session receiverSession = receiver.getSession(SENDER_ID);
        assertNotNull(receiverSession);

        // Create test messages
        long baseSeq = receiverSession.getExpectedSequenceNumber();
        byte[] content1 = "Test 1".getBytes();
        DataMessage msg1 = new DataMessage(baseSeq, content1, null, SENDER_ID);
        byte[] content2 = "Test 2".getBytes();
        DataMessage msg2 = new DataMessage(baseSeq + 1, content2, null, SENDER_ID);

        // Test: adding in-order packet returns it for delivery
        synchronized (receiverSession) {
            List<DataMessage> result1 = receiverSession.addReceivedPacket(msg1);
            assertEquals(1, result1.size(), "In-order packet should be returned for delivery");
            assertEquals(baseSeq + 1, receiverSession.getExpectedSequenceNumber(),
                    "Expected seq should advance after delivery");

            // Test: adding next in-order packet returns it
            List<DataMessage> result2 = receiverSession.addReceivedPacket(msg2);
            assertEquals(1, result2.size(), "Next in-order packet should be returned");
            assertEquals(baseSeq + 2, receiverSession.getExpectedSequenceNumber(),
                    "Expected seq should advance again");

            // Test: adding duplicate (old packet) returns empty list
            List<DataMessage> resultDuplicate = receiverSession.addReceivedPacket(msg1);
            assertEquals(0, resultDuplicate.size(), "Duplicate (old) packet should not be delivered");
        }
    }

    // Tests that duplicate packets are no delivered twice
    @Test
    @Timeout(10)
    public void testNoDuplication_DuplicatePackets() throws Exception {
        CountDownLatch firstMessage = new CountDownLatch(1);
        CountDownLatch secondMessage = new CountDownLatch(1);
        AtomicInteger deliveryCount = new AtomicInteger(0);
        List<String> deliveredMessages = Collections.synchronizedList(new ArrayList<>());

        receiver.setConsensusMessageListener(content -> {
            int count = deliveryCount.incrementAndGet();
            deliveredMessages.add(content);

            if (count == 1) {
                firstMessage.countDown();
            } else if (count == 2) {
                secondMessage.countDown(); // This should NEVER happen
            }
        });

        // Establish connection
        Session senderSession = sender.startConnection(RECEIVER_ID);
        assertNotNull(senderSession);
        Thread.sleep(500);

        Session receiverSession = receiver.getSession(SENDER_ID);
        assertNotNull(receiverSession);

        // Send message normally
        String message = "Test message";
        sender.sendData(RECEIVER_ID, message.getBytes());

        // Wait for first delivery
        assertTrue(firstMessage.await(5, TimeUnit.SECONDS), "First message should be delivered");
        assertEquals(1, deliveryCount.get());

        // Send duplicate with SAME sequence number
        long usedSeqNum = senderSession.getNextSequenceNumber() - 1;
        DataMessage duplicateMsg = new DataMessage(usedSeqNum, message.getBytes(), null, SENDER_ID);
        byte[] hmac = Crypto.generateHMAC(receiverSession.getSessionKey(), duplicateMsg.toJsonString().getBytes());
        DataMessage duplicateWithHmac = new DataMessage(usedSeqNum, message.getBytes(), hmac, SENDER_ID);

        senderSocket.send(duplicateWithHmac.toDatagramPacket(receiverSession.getAddr()));

        // Wait to see if duplicate triggers delivery (it shouldn't)
        boolean duplicateDelivered = secondMessage.await(5, TimeUnit.SECONDS);

        assertFalse(duplicateDelivered, "Duplicate should NOT be delivered");
        assertEquals(1, deliveryCount.get(), "Should only have 1 delivery");
        assertEquals(1, deliveredMessages.size(), "Should only have 1 message");
    }

    // Test that HMAC verification
    @Test
    @Timeout(10)
    public void testAuthenticity_HMACVerification() throws Exception {
        AtomicInteger deliveryCount = new AtomicInteger(0);

        receiver.setConsensusMessageListener(content -> {
            deliveryCount.incrementAndGet();
        });

        // Establish connection
        Session senderSession = sender.startConnection(RECEIVER_ID);
        assertNotNull(senderSession);
        Thread.sleep(500);

        Session receiverSession = receiver.getSession(SENDER_ID);
        assertNotNull(receiverSession);

        // Send valid message
        String validMessage = "Valid message";
        sender.sendData(RECEIVER_ID, validMessage.getBytes());
        Thread.sleep(1000);

        int validDeliveries = deliveryCount.get();
        assertTrue(validDeliveries >= 1, "Valid message should be delivered");

        // Create a message with tampered content and wrong HMAC (simulates an attacker
        // modifying the message)
        long seqNum = receiverSession.getExpectedSequenceNumber();
        String tamperedMessage = "Tampered message";

        // Create message with wrong HMAC (all zeros)
        byte[] wrongHmac = new byte[32];
        DataMessage tamperedMsg = new DataMessage(seqNum, tamperedMessage.getBytes(), wrongHmac, SENDER_ID);

        // Send tampered message from sender's socket to receiver
        senderSocket.send(tamperedMsg.toDatagramPacket(senderSession.getAddr()));

        Thread.sleep(1000);

        // Tampered message should NOT be delivered (HMAC verification should fail)
        assertEquals(validDeliveries, deliveryCount.get(),
                "Tampered message should be rejected due to HMAC verification failure");
    }

    // Tests that messages claiming to be from a different sender are rejected (a
    // sender tries to impersonate another)
    @Test
    @Timeout(10)
    public void testAuthenticity_SenderIDVerification() throws Exception {
        AtomicInteger deliveryCount = new AtomicInteger(0);

        receiver.setConsensusMessageListener(content -> {
            deliveryCount.incrementAndGet();
        });

        // Establish connection
        Session senderSession = sender.startConnection(RECEIVER_ID);
        assertNotNull(senderSession);
        Thread.sleep(500);

        // Send valid message
        String message = "Legitimate message";
        sender.sendData(RECEIVER_ID, message.getBytes());
        Thread.sleep(1000);

        int legitimateDeliveries = deliveryCount.get();
        assertTrue(legitimateDeliveries == 1);

        // Try to forge a message with wrong sender ID in the content
        Session receiverSession = receiver.getSession(SENDER_ID);
        long seqNum = receiverSession.getExpectedSequenceNumber();

        // Create message claiming to be from different sender (ID mismatch in content)
        long fakeSenderId = 999L;
        String fakeContent = "{\"senderId\":" + fakeSenderId + ",\"data\":\"Fake message\"}";
        byte[] hmac = Crypto.generateHMAC(receiverSession.getSessionKey(), fakeContent.getBytes());
        DataMessage fakeMsg = new DataMessage(seqNum, fakeContent.getBytes(), hmac, fakeSenderId);

        // Send forged message from sender to receiver
        senderSocket.send(fakeMsg.toDatagramPacket(senderSession.getAddr()));

        Thread.sleep(1000);

        // Fake message should be rejected (content senderId doesn't match session)
        assertEquals(legitimateDeliveries, deliveryCount.get(),
                "Message with wrong sender ID should be rejected");
    }

}
