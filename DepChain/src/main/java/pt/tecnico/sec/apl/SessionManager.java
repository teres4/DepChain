package pt.tecnico.sec.apl;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collection;

import javax.crypto.SecretKey;

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

    public Session createSession(long senderID, InetSocketAddress addr, SecretKey sessionKey, boolean isClient)
            throws Exception {
        // create a new session and add it to the session manager
        // get socket adress from config
        Session session = new Session(senderID, 1, sessionKey, addr, isClient);
        addSession(senderID, session);
        return session;
    }

    public Collection<Session> getAllSessions() {
        return sessions.values();
    }

}
