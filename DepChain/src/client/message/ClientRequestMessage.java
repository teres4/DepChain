package pt.tecnico.sec.client.message;

import pt.tecnico.sec.apl.message.Message;
import pt.tecnico.sec.apl.message.MessageType;

public class ClientRequestMessage extends Message {
    private final byte[] hmac;

    public ClientRequestMessage() {
        super(MessageType.CLIENT_REQUEST, 0);
        this.hmac = null;}

    public byte[] getHmac() { return hmac; }

    @Override
    public ClientRequestMessage withoutHmac() {
        return new ClientRequestMessage(getSequenceNumber(), null);
    }
    
    @Override
    public String toString() {
        return "ClientRequestMessage{sequenceNumber=" + getSequenceNumber() + "}";
    }
}