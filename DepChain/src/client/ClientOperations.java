package pt.tecnico.sec.client;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.apl.message.DataACKMessage;
import pt.tecnico.sec.client.message.ClientRequestMessage;


public class ClientOperations {
    private AuthPerfLink authPerfLink;
    private int clientId;

    public ClientOperations(int clientId, AuthPerfLink authPerfLink) {
        this.authPerfLink = authPerfLink;
        this.clientId = clientId;
    }

    public String append(String stringToAppend) throws Exception {
        // Create a CLIENT_REQUEST message
        ClientRequestMessage reqMsg = new ClientRequestMessage(0, stringToAppend, this.clientId, null, nonce);
        // TODO send to f+1 replicas
        authPerfLink.sendData(receiverID, reqMsg);
        
        // Wait for an ACK
        while (true) {
            // TODO use data ACK message?
            DataACKMessage reply = authPerfLink.receive();
            if (reply.getType() == MessageType.ACK) {
                    nonce++;
                    return reply.getValue();
            }
        }
    }

}