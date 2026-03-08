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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import pt.tecnico.sec.apl.message.DataMessage;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.DHKeyAgreement;
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
    private DHKeyAgreement senderDH;
    private DHKeyAgreement receiverDH;
    private KeyPair senderKeyPair;
    private KeyPair receiverKeyPair;

    private static final long SENDER_ID = 1L;
    private static final long RECEIVER_ID = 2L;
    private static final int SENDER_PORT = 9001;
    private static final int RECEIVER_PORT = 9002;

    @BeforeEach
    public void setup() throws Exception {
        // Generate key pairs for testing
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        senderKeyPair = keyGen.generateKeyPair();
        receiverKeyPair = keyGen.generateKeyPair();

        // Initialize DH key agreements
        senderDH = new DHKeyAgreement();
        senderDH.initialize();
        receiverDH = new DHKeyAgreement();
        receiverDH.initialize();

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
                senderExecutor, senderSessionManager, senderMemberManager, senderDH);

        receiver = new AuthPerfLink(RECEIVER_ID, receiverKeyPair.getPrivate(), true, receiverSocket,
                receiverExecutor, receiverSessionManager, receiverMemberManager, receiverDH);

        // Start receive threads
        new Thread(() -> sender.receive()).start();
        new Thread(() -> receiver.receive()).start();
    }

    @AfterEach
    public void teardown() {
        if (sender != null) sender.shutdown();
        if (receiver != null) receiver.shutdown();
    }

    private MemberManager createMemberManager(long otherId, int otherPort, 
                                              PublicKey otherPublicKey) {
        MemberManager manager = mock(MemberManager.class);
        InetSocketAddress otherAddr = new InetSocketAddress("localhost", otherPort);
        MemberInfo otherMember = new MemberInfo(otherId, otherPublicKey, "test-key-" + otherId, otherAddr);
        
        // Mock the member manager to return our test member
        when(manager.getMember(otherId)).thenReturn(otherMember);
        when(manager.getMemberByAddress(otherAddr)).thenReturn(otherMember);
        when(manager.getMembers()).thenReturn(new MemberInfo[]{otherMember});
        
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

    // A message sent from a correct sender should be delivered to a correct receiver 
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

    // TODO BELOW TESTS ARE FAILING
    // Test to simulate packet loss and verify retransmission
    // @Test
    // @Timeout(120)
    // public void testReliableDelivery_MultipleMessages() throws Exception {
    //     int messageCount = 10;
    //     CompletableFuture<Void> allMessagesReceived = new CompletableFuture<>();
    //     List<String> receivedMessages = new ArrayList<>();

    //     receiver.setMessageListener(content -> {
    //         synchronized (receivedMessages) {
    //             receivedMessages.add(content);
    //             System.out.println("Received message: " + content); // Debug log

    //             if (receivedMessages.size() == messageCount) {
    //                 allMessagesReceived.complete(null);
    //             }
    //         }
    //     });
        
    //     // Establish connection
    //     Session session = sender.startConnection(RECEIVER_ID);
    //     assertNotNull(session);
    //     Thread.sleep(500);

    //     // Send multiple messages
    //     for (int i = 0; i < messageCount; i++) {
    //         System.out.println("Sending message: Message " + i); // Debug log
    //         sender.sendData(RECEIVER_ID, ("Message " + i).getBytes());
    //     }

    //     // Wait for all messages
    //     allMessagesReceived.get(120, TimeUnit.SECONDS);
    //     assertEquals(messageCount, receivedMessages.size(), 
    //              "Should receive exactly " + messageCount + " messages");
    // }

    // Tests that messages are retransmitted if ACKS are lost at the receiver (simulate packet loss)
    // @Test
    // @Timeout(50)
    // public void testReliableDelivery_ACKPacketLoss() throws Exception {
    //     int messageCount = 4;
    //     CompletableFuture<Void> allMessagesDelivered = new CompletableFuture<>();
    //     List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

    //     receiver.setMessageListener(content -> {
    //         // Simulate packet loss by randomly ignoring messages
    //         if (Math.random() < 0.5) { // Drop 50% of packets for testing
    //             System.out.println("Dropping packet: " + content);
    //             return;
    //         }

    //         receivedMessages.add(content);
    //         System.out.println("Received message: " + content);

    //         if (receivedMessages.size() == messageCount) {
    //             allMessagesDelivered.complete(null);
    //         }
    //     });

    //     // Establish connection
    //     Session session = sender.startConnection(RECEIVER_ID);
    //     assertNotNull(session);
    //     Thread.sleep(500);

    //     // Send multiple messages
    //     for (int i = 0; i < messageCount; i++) {
    //         String message = "Message " + i;
    //         sender.sendData(RECEIVER_ID, message.getBytes());
    //     }

    //     // Wait for all messages to be delivered
    //     assertDoesNotThrow(() -> allMessagesDelivered.get(50, TimeUnit.SECONDS), 
    //                        "All messages should be delivered even with retransmissions");

    //     assertEquals(messageCount, receivedMessages.size(), 
    //                  "All messages should be delivered exactly once");
    // }

    // // Tests that messages are retransmitted if data packets are lost at the receiver (simulate packet loss)
    // @Test
    // @Timeout(50)
    // public void testReliableDelivery_ReceiverPacketLoss() throws Exception {
    //     int messageCount = 4;
    //     CompletableFuture<Void> allMessagesDelivered = new CompletableFuture<>();
    //     List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

    //     receiver.setMessageListener(content -> {
    //         receivedMessages.add(content);
    //         System.out.println("Received message: " + content);

    //         if (receivedMessages.size() == messageCount) {
    //             allMessagesDelivered.complete(null);
    //         }
    //     });

    //     // Establish connection
    //     Session session = sender.startConnection(RECEIVER_ID);
    //     assertNotNull(session);
    //     Thread.sleep(500);

    //     // Send multiple messages
    //     for (int i = 0; i < messageCount; i++) {
    //         // Simulate packet loss by dropping packets at the receiver level
    //         if (Math.random() < 0.5) { // Drop 50% of packets for testing
    //             System.out.println("Sender dropping message: Message " + i);
    //             continue;
    //         }
    //         String message = "Message " + i;
    //         sender.sendData(RECEIVER_ID, message.getBytes());
    //     }

    //     // Wait for all messages to be delivered
    //     assertDoesNotThrow(() -> allMessagesDelivered.get(50, TimeUnit.SECONDS), 
    //                        "All messages should be delivered even with receiver packet loss");

    //     assertEquals(messageCount, receivedMessages.size(), 
    //                  "All messages should be delivered exactly once");
    // }

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

        // Create a message with tampered content and wrong HMAC (simulates an attacker modifying the message)
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



    // Tests that messages claiming to be from a different sender are rejected (a sender tries to impersonate another)
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
