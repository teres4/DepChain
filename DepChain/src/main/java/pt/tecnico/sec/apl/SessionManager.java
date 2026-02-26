package pt.tecnico.sec.apl;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;

import pt.tecnico.sec.utils.Crypto;

public class SessionManager {

    private ConcurrentHashMap<Long, Session> sessions = new ConcurrentHashMap<>();

    public void addSession(long senderID, Session session) {
        sessions.put(senderID, session);
    }

    public Session getSession(long senderID) {
        return sessions.get(senderID);
    }

    public boolean hasSession(long senderID) {
        return sessions.containsKey(senderID);
    }

    public Session createSession(long senderID, InetSocketAddress addr, SecretKey sessionKey) throws Exception {
        // create a new session and add it to the session manager
        // get socket adress from config
        long sequenceNumber = 0;
        Session session = new Session(senderID, sequenceNumber, sessionKey, addr);
        addSession(senderID, session);
        return session;
    }

}
