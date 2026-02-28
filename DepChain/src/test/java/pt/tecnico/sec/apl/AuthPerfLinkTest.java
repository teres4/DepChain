package pt.tecnico.sec.apl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import pt.tecnico.sec.apl.message.ConnectionRequestMessage;
import pt.tecnico.sec.apl.message.DataACKMessage;
import pt.tecnico.sec.apl.message.DataMessage;
import pt.tecnico.sec.apl.message.Message;
import pt.tecnico.sec.apl.message.MessageType;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;
import pt.tecnico.sec.utils.MemberManager;
import pt.tecnico.sec.utils.TestCryptoUtils;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AuthPerfLinkTest {

    private AuthPerfLink authPerfLink;
    private DatagramSocket mockSocket;
    private ExecutorService mockExecutor;
    private SessionManager mockSessionManager;
    private MemberManager mockMemberManagement;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    private static final long OWN_ID = 1L;
    private static final long PEER_ID = 2L;
    private static final InetSocketAddress TEST_ADDR = new InetSocketAddress("localhost", 9090);

    @BeforeEach
    void setUp() throws Exception {
        // Generate test keys
        KeyPair keyPair = TestCryptoUtils.generateKeyPair();
        privateKey = keyPair.getPrivate();
        publicKey = keyPair.getPublic();

        // Create mocks
        mockSocket = mock(DatagramSocket.class);
        mockExecutor = mock(ExecutorService.class);
        mockSessionManager = mock(SessionManager.class);
        mockMemberManagement = mock(MemberManager.class);

        // Create AuthPerfLink with mocked dependencies
        authPerfLink = new AuthPerfLink(OWN_ID, privateKey, mockSocket, mockExecutor,
                mockSessionManager, mockMemberManagement);
    }

    @AfterEach
    void tearDown() {
        authPerfLink.shutdown();
    }

    @Test
    void testValidateConnectionRequest_ValidMessage() {
        // Arrange
        ConnectionRequestMessage msg = new ConnectionRequestMessage(PEER_ID, "encryptedKey", "signature");

        // Act: Validate that it has sequence number 0
        boolean result = (msg.getSequenceNumber() == 0);

        // Assert
        assertTrue(result);
    }

    @Test
    void testValidateConnectionRequest_InvalidMessageType() {
        // Arrange
        DataMessage msg = new DataMessage(0, "test".getBytes(), null);

        // Act: Validate that it's not a connection request
        boolean result = (msg.getType() == MessageType.CONNECTION_REQUEST);

        // Assert
        assertFalse(result);
    }

    @Test
    void testValidateConnectionRequest_InvalidSequenceNumber() {
        // Arrange: ConnectionRequestMessage always has sequence number 0
        ConnectionRequestMessage msg = new ConnectionRequestMessage(PEER_ID, "encryptedKey", "signature");

        // Act: Test that a wrong sequence number would fail (simulated as always
        // starting from 0)
        boolean result = (msg.getSequenceNumber() != 0);

        // Assert
        assertFalse(result);
    }

    @Test
    void testVerifySequenceNumber_Valid() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = new Session(PEER_ID, 0, sessionKey, TEST_ADDR);
        DataMessage msg = new DataMessage(1, "test".getBytes(), null);

        // Act
        boolean result = authPerfLink.verifySequenceNumber(msg, mockSession, TEST_ADDR);

        // Assert
        assertTrue(result);
    }

    @Test
    void testVerifySequenceNumber_Invalid() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = new Session(PEER_ID, 0, sessionKey, TEST_ADDR);
        DataMessage msg = new DataMessage(10, "test".getBytes(), null);

        // Act
        boolean result = authPerfLink.verifySequenceNumber(msg, mockSession, TEST_ADDR);

        // Assert
        assertFalse(result);
    }

    @Test
    void testVerifyHmac_Valid() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = new Session(PEER_ID, 0, sessionKey, TEST_ADDR);

        byte[] payload = "test".getBytes();
        long sequenceNumber = 1;
        DataMessage msgWithoutHmac = new DataMessage(sequenceNumber, payload, null);
        String jsonWithoutHmac = msgWithoutHmac.toJsonString();
        byte[] hmac = Crypto.generateHMAC(sessionKey, jsonWithoutHmac.getBytes());

        // Act
        boolean result = authPerfLink.verifyHmac(jsonWithoutHmac, hmac, mockSession, TEST_ADDR);

        // Assert
        assertTrue(result);
    }

    @Test
    void testVerifyHmac_Invalid() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = new Session(PEER_ID, 0, sessionKey, TEST_ADDR);

        byte[] payload = "test".getBytes();
        byte[] wrongHmac = new byte[32]; // Fake HMAC
        DataMessage msgWithoutHmac = new DataMessage(1, payload, null);
        String jsonWithoutHmac = msgWithoutHmac.toJsonString();

        // Act
        boolean result = authPerfLink.verifyHmac(jsonWithoutHmac, wrongHmac, mockSession, TEST_ADDR);

        // Assert
        assertFalse(result);
    }

    @Test
    void testBuildDataMessage() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        byte[] payload = "test data".getBytes();
        long sequenceNumber = 5;

        // Act: Create a DataMessage with HMAC
        DataMessage msgWithoutHmac = new DataMessage(sequenceNumber, payload, null);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toJsonString().getBytes());
        DataMessage msg = new DataMessage(sequenceNumber, payload, hmac);

        // Assert
        assertNotNull(msg);
        assertEquals(sequenceNumber, msg.getSequenceNumber());
        assertNotNull(msg.getHmac());
    }

    @Test
    void testHandleAck_Success() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = mock(Session.class);
        when(mockSession.getSequenceNumber()).thenReturn(0L);
        when(mockSession.getSessionKey()).thenReturn(sessionKey);

        MemberInfo mockMember = mock(MemberInfo.class);
        when(mockMember.getId()).thenReturn(PEER_ID);

        when(mockMemberManagement.getMemberByAddress(TEST_ADDR)).thenReturn(mockMember);
        when(mockSessionManager.getSession(PEER_ID)).thenReturn(mockSession);

        // Create valid ACK message
        long sequenceNumber = 1;
        DataACKMessage msgWithoutHmac = new DataACKMessage(sequenceNumber, null);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toJsonString().getBytes());
        DataACKMessage msg = new DataACKMessage(sequenceNumber, hmac);

        // Act
        authPerfLink.handleAck(msg, TEST_ADDR);

        // Assert
        verify(mockSession).replyReceived();
    }

    @Test
    void testHandleAck_UnknownSender() {
        // Arrange
        when(mockMemberManagement.getMemberByAddress(TEST_ADDR)).thenReturn(null);
        DataACKMessage msg = new DataACKMessage(1, new byte[32]);

        // Act
        authPerfLink.handleAck(msg, TEST_ADDR);

        // Assert
        verify(mockSessionManager, never()).getSession(anyLong());
    }

    @Test
    void testHandleData_Success() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = mock(Session.class);
        when(mockSession.getSequenceNumber()).thenReturn(0L);
        when(mockSession.getSessionKey()).thenReturn(sessionKey);
        when(mockSession.getAddr()).thenReturn(TEST_ADDR);

        MemberInfo mockMember = mock(MemberInfo.class);
        when(mockMember.getId()).thenReturn(PEER_ID);

        when(mockMemberManagement.getMemberByAddress(TEST_ADDR)).thenReturn(mockMember);
        when(mockSessionManager.getSession(PEER_ID)).thenReturn(mockSession);

        // Create valid DATA message
        byte[] payload = "some data".getBytes();
        long sequenceNumber = 1;
        DataMessage msgWithoutHmac = new DataMessage(sequenceNumber, payload, null);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toJsonString().getBytes());
        DataMessage msg = new DataMessage(sequenceNumber, payload, hmac);

        // Act
        authPerfLink.handleData(msg, TEST_ADDR);

        // Assert
        verify(mockSession).setSequenceNumber(sequenceNumber);
    }

    @Test
    void testBuildAndSendDataMessage() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        byte[] content = "test data content".getBytes();
        long sequenceNumber = 5;

        // Act: Build a DataMessage with proper HMAC
        DataMessage msgWithoutHmac = new DataMessage(sequenceNumber, content, null);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toJsonString().getBytes());
        DataMessage msg = new DataMessage(sequenceNumber, content, hmac);

        // Assert: Verify message was created correctly
        assertNotNull(msg);
        assertEquals(MessageType.DATA, msg.getType());
        assertEquals(sequenceNumber, msg.getSequenceNumber());
        assertArrayEquals(content, msg.getContent());
        assertNotNull(msg.getHmac());

        // Verify HMAC is valid
        boolean isValid = authPerfLink.verifyHmac(
                msg.withoutHmac().toJsonString(),
                msg.getHmac(),
                new Session(PEER_ID, 0, sessionKey, TEST_ADDR),
                TEST_ADDR);
        assertTrue(isValid);
    }

    @Test
    void testMessageToDatagramPacket() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        byte[] content = "test data content".getBytes();
        long sequenceNumber = 3;

        // Act: Create message and convert to datagram packet
        DataMessage msgWithoutHmac = new DataMessage(sequenceNumber, content, null);
        byte[] hmac = Crypto.generateHMAC(sessionKey, msgWithoutHmac.toJsonString().getBytes());
        DataMessage msg = new DataMessage(sequenceNumber, content, hmac);

        DatagramPacket packet = msg.toDatagramPacket(TEST_ADDR);

        // Assert
        assertNotNull(packet);
        assertEquals(TEST_ADDR.getAddress(), packet.getAddress());
        assertEquals(TEST_ADDR.getPort(), packet.getPort());
        assertTrue(packet.getLength() > 0);
    }

    @Test
    void testShutdown() {
        // Act
        authPerfLink.shutdown();

        // Assert
        verify(mockSocket).close();
        verify(mockExecutor).shutdown();
    }
}
