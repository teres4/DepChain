package pt.tecnico.sec.apl;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

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

}
