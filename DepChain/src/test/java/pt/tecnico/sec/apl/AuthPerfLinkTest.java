package pt.tecnico.sec.apl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        Message msg = new Message(MessageType.CONNECTION_REQUEST, "test".getBytes(), 0, null);

        // Act
        boolean result = authPerfLink.validateConnectionRequest(msg, TEST_ADDR);

        // Assert
        assertTrue(result);
    }

    @Test
    void testValidateConnectionRequest_InvalidMessageType() {
        // Arrange
        Message msg = new Message(MessageType.DATA, "test".getBytes(), 0, null);

        // Act
        boolean result = authPerfLink.validateConnectionRequest(msg, TEST_ADDR);

        // Assert
        assertFalse(result);
    }

    @Test
    void testValidateConnectionRequest_InvalidSequenceNumber() {
        // Arrange
        Message msg = new Message(MessageType.CONNECTION_REQUEST, "test".getBytes(), 5, null);

        // Act
        boolean result = authPerfLink.validateConnectionRequest(msg, TEST_ADDR);

        // Assert
        assertFalse(result);
    }

    @Test
    void testVerifySequenceNumber_Valid() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = new Session(PEER_ID, 0, sessionKey, TEST_ADDR);
        Message msg = new Message(MessageType.DATA, "test".getBytes(), 1, null);

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
        Message msg = new Message(MessageType.DATA, "test".getBytes(), 10, null);

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
        Message msg = new Message(MessageType.DATA, payload, sequenceNumber);

        byte[] jsonMessage = msg.toJsonString().getBytes();
        byte[] hmac = Crypto.generateHMAC(sessionKey, jsonMessage);
        msg.setHmac(hmac);

        // Act
        boolean result = authPerfLink.verifyHmac(msg, mockSession, TEST_ADDR);

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
        Message msg = new Message(MessageType.DATA, payload, 1, wrongHmac);

        // Act
        boolean result = authPerfLink.verifyHmac(msg, mockSession, TEST_ADDR);

        // Assert
        assertFalse(result);
    }

    @Test
    void testPrepareResponse() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        String payload = "response payload";
        long sequenceNumber = 5;

        // Act
        DatagramPacket packet = authPerfLink.prepareResponse(
                MessageType.ACK, payload, sequenceNumber, sessionKey, TEST_ADDR);

        // Assert
        assertNotNull(packet);
        assertEquals(TEST_ADDR.getAddress(), packet.getAddress());
        assertEquals(TEST_ADDR.getPort(), packet.getPort());
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

        // Create valid ACK message with only 3 fields
        byte[] payload = "ACK".getBytes();
        long sequenceNumber = 1;
        Message msg = new Message(MessageType.ACK, payload, sequenceNumber);

        // Generate HMAC separately
        byte[] hmac = Crypto.generateHMAC(sessionKey, msg.toJsonString().getBytes());
        msg.setHmac(hmac);

        // Act
        authPerfLink.handleAck(msg, TEST_ADDR);

        // Assert
        verify(mockSession).replyReceived();
    }

    @Test
    void testHandleAck_UnknownSender() {
        // Arrange
        when(mockMemberManagement.getMemberByAddress(TEST_ADDR)).thenReturn(null);
        Message msg = new Message(MessageType.ACK, "test".getBytes(), 1, new byte[32]);

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

        MemberInfo mockMember = mock(MemberInfo.class);
        when(mockMember.getId()).thenReturn(PEER_ID);

        when(mockMemberManagement.getMemberByAddress(TEST_ADDR)).thenReturn(mockMember);
        when(mockSessionManager.getSession(PEER_ID)).thenReturn(mockSession);

        // Create valid DATA message with only 3 fields
        byte[] payload = "some data".getBytes();
        long sequenceNumber = 1;
        Message msg = new Message(MessageType.DATA, payload, sequenceNumber);

        // Generate HMAC separately
        byte[] hmac = Crypto.generateHMAC(sessionKey, msg.toJsonString().getBytes());
        msg.setHmac(hmac);

        // Act
        authPerfLink.handleData(msg, TEST_ADDR);

        // Assert
        verify(mockSession).replyReceived();
    }

    @Test
    void testSendAck() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = mock(Session.class);
        when(mockSession.getAddr()).thenReturn(TEST_ADDR);
        when(mockSession.getSequenceNumber()).thenReturn(5L);
        when(mockSession.getSessionKey()).thenReturn(sessionKey);

        MemberInfo mockMember = mock(MemberInfo.class);
        when(mockMember.getId()).thenReturn(PEER_ID);
        when(mockMemberManagement.getMemberByAddress(TEST_ADDR)).thenReturn(mockMember);

        // Act
        authPerfLink.sendAck(mockSession, mockSession.getSequenceNumber());

        // Assert
        verify(mockSocket).send(any(DatagramPacket.class));
    }

    @Test
    void testSendData() throws Exception {
        // Arrange
        SecretKey sessionKey = Crypto.generateSecretKey();
        Session mockSession = mock(Session.class);
        when(mockSession.getAddr()).thenReturn(TEST_ADDR);
        when(mockSession.getSequenceNumber()).thenReturn(3L);
        when(mockSession.getSessionKey()).thenReturn(sessionKey);

        String content = "test data content";

        // Act
        authPerfLink.sendData(mockSession, content);

        // Assert
        ArgumentCaptor<DatagramPacket> packetCaptor = ArgumentCaptor.forClass(DatagramPacket.class);
        verify(mockSocket).send(packetCaptor.capture());

        DatagramPacket sentPacket = packetCaptor.getValue();
        assertEquals(TEST_ADDR.getAddress(), sentPacket.getAddress());
        assertEquals(TEST_ADDR.getPort(), sentPacket.getPort());
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
