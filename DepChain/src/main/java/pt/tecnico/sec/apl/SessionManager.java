package pt.tecnico.sec.apl;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

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
        final SecretKey key = sessionKey;
        final boolean client = isClient;
        Session session = sessions.computeIfAbsent(senderID, id -> new Session(id, 1, key, addr, null, client));
        if (sessionKey != null && session.getSessionKey() == null) {
            session.setSessionKey(sessionKey);
        }
        return session;
    }

    public Collection<Session> getAllSessions() {
        return sessions.values();
    }

}
